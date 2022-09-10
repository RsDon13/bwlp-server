package org.openslx.bwlp.sat.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.sql.SQLException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

public class Identity {

	private static final Logger LOGGER = LogManager.getLogger(Identity.class);

	private static final String ALIAS = "dozmod";

	private static final String PASSWORD = "donotchangeme";

	private static KeyStore currentKeyStore = null;

	public static KeyStore loadCertificate() {
		if (currentKeyStore != null)
			return currentKeyStore;
		KeyStore keystore = null;
		try {
			keystore = DbConfiguration.loadKeyStore(PASSWORD);
		} catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | SQLException
				| IOException e) {
			LOGGER.error("Could not load existing keystore from database", e);
			LOGGER.info("Will generate a new temporary key. Please fix the existing key"
					+ " or delete it to permanently generate a new one");
		}
		if (keystore == null) {
			if (!generateCertificate()) {
				LOGGER.error("Could not create certificate, encrypted connections not supported");
				return null;
			}
			try {
				keystore = DbConfiguration.loadKeyStore(PASSWORD);
			} catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | SQLException
					| IOException e) {
				LOGGER.error("Error loading key", e);
			}
			if (keystore == null) {
				LOGGER.error("Could not load freshly generated certificate back from db. Something's fishy.");
				return null;
			}
		}
		currentKeyStore = keystore;
		return keystore;
	}

	public static SSLContext getSSLContext() {
		if (loadCertificate() == null)
			return null;
		KeyManagerFactory kmf;
		try {
			kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(currentKeyStore, PASSWORD.toCharArray());
		} catch (NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException e) {
			LOGGER.warn("Could not create a key manager factory, SSL unavailable", e);
			return null;
		}
		SSLContext sslContext;
		try {
			sslContext = SSLContext.getInstance("TLSv1.2");
		} catch (NoSuchAlgorithmException e) {
			LOGGER.warn("Could not create a TLS1.2 context, SSL unavailable", e);
			return null;
		}
		KeyManager[] keyManagers = kmf.getKeyManagers();
		try {
			sslContext.init(keyManagers, null, null);
		} catch (KeyManagementException e) {
			LOGGER.warn("Could not find a suitable cert/key in the keystore. SSL unavailable", e);
		}
		return sslContext;
	}

	private static boolean generateCertificate() {
		File tmpFile = null;
		try {
			tmpFile = File.createTempFile("certfile", ".jks");
			tmpFile.delete();
		} catch (IOException e1) {
			LOGGER.error("Could not generate temp file for self-signed cert container", e1);
			return false;
		}
		try {
			LOGGER.info("Generating certificate for this server...");
			final Process proc;
			try {
				proc = Runtime.getRuntime().exec(
						new String[] { "keytool", "-genkeypair", "-alias", ALIAS, "-keyalg", "rsa",
								"-validity", "3000", "-keypass", PASSWORD, "-storepass", PASSWORD,
								"-keystore", tmpFile.getAbsolutePath(), "-storetype", "JKS", "-dname",
								"CN=dozmodserver" });
				QuickTimer.scheduleOnce(new Task() {
					@Override
					public void fire() {
						try {
							proc.exitValue();
							proc.destroy();
						} catch (IllegalThreadStateException e) {
						}
					}
				}, 10000);
			} catch (IOException e) {
				LOGGER.error("Launching keytool failed", e);
				return false;
			}
			int exitCode;
			try {
				exitCode = proc.waitFor();
			} catch (InterruptedException e) {
				LOGGER.warn("Got interrupted while creating the certificate");
				proc.destroy();
				return false;
			}
			if (exitCode != 0) {
				LOGGER.warn("keytool returned exit code " + exitCode);
				InputStream is = proc.getInputStream();
				BufferedReader br = new BufferedReader(new InputStreamReader(is));
				try {
					String line;
					while (null != (line = br.readLine())) {
						LOGGER.info(line);
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				return false;
			}
			LOGGER.info("Certificate successfully created!");
			try {
				DbConfiguration.saveKeyStore(tmpFile);
			} catch (SQLException | IOException e) {
				LOGGER.error("Could not import generated keystore to database", e);
				return false;
			}
			return true;
		} finally {
			tmpFile.delete();
		}
	}

}
