package org.openslx.bwlp.sat.database.mappers;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.mail.MailQueue.MailConfig;
import org.openslx.bwlp.sat.mail.MailTemplate;
import org.openslx.bwlp.sat.mail.MailTemplateConfiguration;
import org.openslx.bwlp.sat.mail.MailTemplatePlain.Template;
import org.openslx.bwlp.thrift.iface.SatelliteConfig;
import org.openslx.util.Json;

public class DbConfiguration {

	private static final Logger LOGGER = LogManager.getLogger(DbConfiguration.class);

	private static final String KEY_CERTIFICATE = "certstore";

	private static final String KEY_MAILCONFIG = "mailconfig";

	private static final String KEY_TEMPLATES = "templates";

	private static final String KEY_LIMITS = "runtimelimits";

	static {
		Json.registerThriftClass(SatelliteConfig.class);
	}

	public static KeyStore loadKeyStore(String password)
			throws KeyStoreException, SQLException, NoSuchAlgorithmException, CertificateException, IOException {
		KeyStore keystore = KeyStore.getInstance("JKS");
		InputStream stream = retrieveStream(KEY_CERTIFICATE);
		if (stream == null)
			return null;
		keystore.load(stream, password.toCharArray());
		return keystore;
	}

	public static void saveKeyStore(File file) throws SQLException, FileNotFoundException, IOException {
		store(KEY_CERTIFICATE, new FileInputStream(file));
	}

	private static void store(String configKey, InputStream stream) throws IOException, SQLException {
		store(configKey, IOUtils.toByteArray(stream));
	}

	private static void store(String configKey, byte[] value) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection
					.prepareStatement("INSERT INTO configuration" + " (parameter, value) VALUES (:parameter, :value)"
							+ " ON DUPLICATE KEY UPDATE value = VALUES(value)");
			stmt.setString("parameter", configKey);
			stmt.setBinary("value", value);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbConfiguration.store()", e);
			throw e;
		}
	}

	private static InputStream retrieveStream(String configKey) throws SQLException {
		byte[] data = retrieve(configKey);
		if (data == null)
			return null;
		return new ByteArrayInputStream(data);
	}

	private static byte[] retrieve(String configKey) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection
					.prepareStatement("SELECT value FROM configuration" + " WHERE parameter = :parameter LIMIT 1");
			stmt.setString("parameter", configKey);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return null;
			return rs.getBytes("value");
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbConfiguration.retrieve()", e);
			throw e;
		}
	}

	/**
	 * Returns mailing configuration (SMTP) from data base.
	 * 
	 * @return mailing configuration (SMTP) from data base.
	 * @throws SQLException
	 */
	public static MailConfig getMailConfig() throws SQLException {
		byte[] conf = retrieve(KEY_MAILCONFIG);
		if (conf == null)
			return null;
		return Json.deserialize(new String(conf, StandardCharsets.UTF_8), MailConfig.class);
	}

	/**
	 * Return satellite runtime config.
	 *
	 * @return
	 * @throws SQLException
	 */
	public static SatelliteConfig getSatelliteConfig() throws SQLException {
		byte[] conf = retrieve(KEY_LIMITS);
		if (conf == null)
			return null;
		return Json.deserialize(new String(conf, StandardCharsets.UTF_8), SatelliteConfig.class);
	}
	
	public static void setSatelliteConfig(SatelliteConfig config) throws SQLException {
		store(KEY_LIMITS, Json.serialize(config).getBytes(StandardCharsets.UTF_8));
	}
	
	private static MailTemplateConfiguration getExistingMailTemplates()
	{
		MailTemplateConfiguration templateConf = null;
		try {
			byte[] raw = retrieve(KEY_TEMPLATES);
			if (raw != null) {
				String json = new String(raw, StandardCharsets.UTF_8);
				templateConf = Json.deserialize(json, MailTemplateConfiguration.class);
			}
		} catch (Exception e) {
			LOGGER.debug("Cannot get mail templates from db", e);
		}
		return templateConf;
	}

	/**
	 * access the database to read the mail templates. If the template is not
	 * found a hard-coded configuration is used and is merged with the database.
	 * 
	 * @param name name of the desired mail template
	 * 
	 * @return the mail template with the given name or NULL if no such template
	 * could be found.
	 */
	public static MailTemplate getMailTemplate(Template name) {
		/* Try to get config from DB */
		MailTemplateConfiguration templateConf = getExistingMailTemplates();

		/* Case 1: Nothing in DB */
		if (templateConf == null) {
			/* save default to db */
			LOGGER.debug("No template config in DB -> save the default config to DB");
			templateConf = MailTemplateConfiguration.defaultTemplateConfiguration;
			try {
				store(KEY_TEMPLATES, Json.serialize(templateConf).getBytes(StandardCharsets.UTF_8));
			} catch (SQLException e) {
			}
		}

		/* Case 2: DB has config but not the template */
		if (templateConf != null && templateConf.getByName(name) == null
				&& MailTemplateConfiguration.defaultTemplateConfiguration.getByName(name) != null) {
			/* merge default config with templateConf */
			LOGGER.debug("DB template config does not contain a template for " + name);
			MailTemplateConfiguration newConf = templateConf.merge(MailTemplateConfiguration.defaultTemplateConfiguration);
			try {
				store(KEY_TEMPLATES, Json.serialize(newConf).getBytes(StandardCharsets.UTF_8));
				templateConf = newConf;
			} catch (SQLException e) {
			}
		}
		/* Case 3: DB has config and has the template */
		if (templateConf != null && templateConf.getByName(name) != null) {
			return templateConf.getByName(name);
		}
		/* CASE 4: Neither in DB nor in default */
		LOGGER.debug("Template with name \"" + name + "\" could not be found");
		return null;
	}
	
	public static void updateMailTemplates(boolean resetExisting)
	{
		MailTemplateConfiguration conf = null;
		if (!resetExisting) {
			conf = getExistingMailTemplates();
			if (conf != null) {
				conf = conf.merge(MailTemplateConfiguration.defaultTemplateConfiguration);
			}
		}
		if (conf == null) {
			conf = MailTemplateConfiguration.defaultTemplateConfiguration;
		}
		try {
			store(KEY_TEMPLATES, Json.serialize(conf).getBytes(StandardCharsets.UTF_8));
		} catch (SQLException e) {
		}
	}

}
