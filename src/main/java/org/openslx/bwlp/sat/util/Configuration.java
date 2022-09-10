package org.openslx.bwlp.sat.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.openslx.util.Util;

public class Configuration {

	private static final Logger LOGGER = LogManager.getLogger(Configuration.class);
	private static final DateTimeFormatter subdirDate = DateTimeFormat.forPattern("yy-MM");

	private static final String DEFAULT_WEBSERVER_BIND_ADDRESS_LOCAL = "127.0.0.1";

	private static File vmStoreBasePath;
	private static File vmStoreProdPath;
	private static String dbUri;
	private static String dbUsername;
	private static String dbPassword;
	private static String masterAddress;
	private static boolean masterSsl = true;
	private static int masterPort = 9091;
	private static boolean webServerBindLocalhost = true;
	private static String dbLocationTable;
	private static SSLContext ctx = null;

	public static boolean load() throws IOException {
		// Load configuration from java properties file
		Properties prop = new Properties();
		InputStream in = new FileInputStream("./config.properties");
		try {
			prop.load(in);
		} finally {
			in.close();
		}

		vmStoreBasePath = new File(prop.getProperty("vmstore.path"));
		vmStoreProdPath = new File(vmStoreBasePath, "bwlehrpool_store");
		dbUri = prop.getProperty("db.uri");
		dbUsername = prop.getProperty("db.username");
		dbPassword = prop.getProperty("db.password");
		dbLocationTable = prop.getProperty("db.location-table");
		masterAddress = prop.getProperty("master.address");
		if (!Util.isEmptyString(prop.getProperty("master.ssl"))) {
			masterSsl = Boolean.parseBoolean(prop.getProperty("master.ssl"));
		}
		try {
			masterPort = Integer.parseInt(prop.getProperty("master.port"));
		} catch (Exception e) {
		}

		if (!Util.isEmptyString(prop.getProperty("webserver.bindLocalhost"))) {
			webServerBindLocalhost = Boolean.parseBoolean(prop.getProperty("webserver.bindLocalhost"));
		}

		// Currently all fields are mandatory but there might be optional settings in the future
		return vmStoreBasePath != null && dbUri != null && dbUsername != null && dbPassword != null;
	}

	// Static ("real") fields

	/**
	 * Get the path of the VM storage space. Traditionally, this is
	 * '/srv/openslx/nfs'.
	 * 
	 * @return path of the VM storage
	 */
	public static File getVmStoreBasePath() {
		return vmStoreBasePath;
	}

	public static String getDbUri() {
		return dbUri;
	}

	public static String getDbUsername() {
		return dbUsername;
	}

	public static String getDbPassword() {
		return dbPassword;
	}

	public static String getDbLocationTable() {
		return dbLocationTable;
	}

	public static File getVmStoreProdPath() {
		return vmStoreProdPath;
	}

	public static String getMasterServerAddress() {
		return masterAddress;
	}

	public static boolean getMasterServerSsl() {
		return masterSsl;
	}

	public static int getMasterServerPort() {
		return masterPort;
	}

	public static boolean getWebServerBindLocalhost() {
		return webServerBindLocalhost;
	}

	public static String getWebServerBindAddressLocal() {
		if (getWebServerBindLocalhost()) {
			return DEFAULT_WEBSERVER_BIND_ADDRESS_LOCAL;
		} else {
			// do not bind to the localhost address
			return null;
		}
	}

	// Dynamically Computed fields

	public static File getCurrentVmStorePath() {
		return new File(vmStoreProdPath, subdirDate.print(System.currentTimeMillis()));
	}

	public static SSLContext getMasterServerSslContext() throws NoSuchAlgorithmException,
			KeyManagementException {
		if (!getMasterServerSsl())
			throw new RuntimeException("SSL not activated");
		if (ctx == null) {
			synchronized (LOGGER) {
				if (ctx == null) {
					ctx = SSLContext.getInstance("TLSv1.2");
					ctx.init(null, null, null);
				}
			}
		}
		return ctx;
	}

}
