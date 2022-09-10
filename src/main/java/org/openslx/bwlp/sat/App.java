package org.openslx.bwlp.sat;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.openslx.bwlp.sat.database.Updater;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.fileserv.FileServer;
import org.openslx.bwlp.sat.maintenance.DeleteOldImages;
import org.openslx.bwlp.sat.maintenance.DeleteOldLectures;
import org.openslx.bwlp.sat.maintenance.DeleteOldUsers;
import org.openslx.bwlp.sat.maintenance.MailFlusher;
import org.openslx.bwlp.sat.maintenance.SendExpireWarning;
import org.openslx.bwlp.sat.thrift.BinaryListener;
import org.openslx.bwlp.sat.thrift.ServerHandler;
import org.openslx.bwlp.sat.thrift.cache.OperatingSystemList;
import org.openslx.bwlp.sat.thrift.cache.OrganizationList;
import org.openslx.bwlp.sat.thrift.cache.VirtualizerList;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.sat.util.Identity;
import org.openslx.bwlp.sat.web.WebServer;
import org.openslx.bwlp.thrift.iface.TInvalidTokenException;
import org.openslx.sat.thrift.version.Version;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.thrifthelper.ThriftManager.ErrorCallback;
import org.openslx.util.AppUtil;
import org.openslx.util.QuickTimer;

import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;

public class App {

	private static Logger LOGGER = LogManager.getLogger(App.class);

	private static final String NAME = "bwLehrpool-Server";

	private static final Set<String> failFastMethods = new HashSet<>();

	public static void main(String[] args) throws TTransportException, NoSuchAlgorithmException, IOException,
			KeyManagementException
	{
		// setup basic logging appender to log output on console if no external appender (log4j.properties) is configured
		if (org.apache.logging.log4j.core.Logger.class.cast(LogManager.getRootLogger()).getAppenders().isEmpty()) {
			Configurator.initialize(new DefaultConfiguration());
		}

		// get Configuration
		try {
			LOGGER.info("Loading configuration");
			Configuration.load();
		} catch (Exception e1) {
			LOGGER.fatal("Could not load configuration", e1);
			System.exit(1);
		}

		AppUtil.logHeader(LOGGER, App.NAME, App.class.getPackage().getImplementationVersion());
		AppUtil.logProperty(LOGGER, "rpc.version", Long.toString(Version.VERSION));
		AppUtil.logProperty(LOGGER, "server.features", SupportedFeatures.getFeatureString());

		// Update database schema if applicable
		try {
			Updater.updateDatabase();
			RuntimeConfig.get();
			DbConfiguration.updateMailTemplates(false);
		} catch (SQLException e1) {
			LOGGER.fatal("Updating/checking the database layout failed.");
			return;
		}

		if (Identity.loadCertificate() == null) {
			LOGGER.error("Could not set up TLS/SSL requirements, exiting");
			System.exit(1);
		}

		failFastMethods.add("getVirtualizers");
		failFastMethods.add("getOperatingSystems");
		failFastMethods.add("getOrganizations");

		ThriftManager.setMasterErrorCallback(new ErrorCallback() {

			@Override
			public boolean thriftError(int failCount, String method, Throwable t) {
				if (failFastMethods.contains(method))
					return false;
				if (failCount > 2 || t == null) {
					LOGGER.warn("Thrift Client error for " + method + ", FAIL.");
					return false;
				}
				if (t instanceof TInvalidTokenException)
					return false;
				if (((TException) t).getCause() == null) {
					LOGGER.info("Thrift error " + t.toString() + " for "
							+ method + ", retrying...");
				} else {
					LOGGER.info("Thrift error " + ((TException) t).getCause().toString() + " for "
							+ method + ", retrying...");
				}
				try {
					Thread.sleep(failCount * 250);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return true;
			}
		});

		SSLContext ctx = null;
		if (Configuration.getMasterServerSsl()) {
			ctx = Configuration.getMasterServerSslContext();
		}
		ThriftManager.setMasterServerAddress(ctx, Configuration.getMasterServerAddress(),
				Configuration.getMasterServerPort(), 30000);

		// Load useful things from master server
		if (VirtualizerList.get() == null || VirtualizerList.get().isEmpty()) {
			LOGGER.fatal("Could not get initial virtualizer list from master server."
					+ " Please make sure this server can connect to the internet.");
			return;
		}
		if (OrganizationList.get() == null || OrganizationList.get().isEmpty()) {
			LOGGER.fatal("Could not get initial organization list from master server."
					+ " Please make sure this server can connect to the internet.");
			return;
		}
		if (OperatingSystemList.get() == null || OperatingSystemList.get().isEmpty()) {
			LOGGER.fatal("Could not get initial operating system list from master server."
					+ " Please make sure this server can connect to the internet.");
			return;
		}

		// Start file transfer server
		if (!FileServer.instance().start()) {
			LOGGER.error("Could not start internal file server.");
			return;
		}

		// Start watch dog to ensure nobody else is messing with the vmstore
		QuickTimer.scheduleAtFixedDelay(new StorageUseCheck(), 10000, 60000);
		// Set a flag that we need to convert userids if applicable
		DbUser.checkIfLegacyUsersExist();

		// Set up maintenance tasks
		DeleteOldImages.init();
		SendExpireWarning.init();
		MailFlusher.init();
		DeleteOldLectures.init();
		DeleteOldUsers.init();

		// Start Thrift Server
		Thread t;
		// Plain
		t = new Thread(new BinaryListener(9090, false));
		t.setDaemon(true);
		t.start();
		// SSL
		t = new Thread(new BinaryListener(9091, true));
		t.start();
		// Start httpd
		t = new Thread(new WebServer(9080));
		t.setDaemon(true);
		t.start();
		// Start armeria server
		ServerBuilder sb = Server.builder();
		sb.http(9070);
		sb.service("/", THttpService.of(new ServerHandler(), ThriftSerializationFormats.JSON));
		Server server = sb.build();
		server.start();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				QuickTimer.cancel();
				LOGGER.info("All services and workers shut down, exiting...");
			}
		});
	}
}
