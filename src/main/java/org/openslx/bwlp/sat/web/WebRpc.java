package org.openslx.bwlp.sat.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.login.LoginException;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.mail.MailTemplate;
import org.openslx.bwlp.sat.mail.MailTemplatePlain.Template;
import org.openslx.bwlp.sat.mail.SmtpMailer;
import org.openslx.bwlp.sat.mail.SmtpMailer.EncryptionMode;
import org.openslx.bwlp.sat.maintenance.DeleteOldImages;
import org.openslx.bwlp.sat.maintenance.ImageValidCheck;
import org.openslx.bwlp.sat.maintenance.ImageValidCheck.CheckResult;
import org.openslx.bwlp.sat.maintenance.ImageValidCheck.SubmitResult;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.util.Json;
import org.openslx.util.Util;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;

public class WebRpc {

	private static final Logger LOGGER = LogManager.getLogger(WebRpc.class);

	public static Response handle(String uri, Map<String, String> params) {
		if (uri.equals("mailtest")) {
			return mailTest(params);
		}
		if (uri.equals("delete-images")) {
			return deleteImages();
		}
		if (uri.equals("start-image-check")) {
			return checkImage(params);
		}
		if (uri.equals("query-image-check")) {
			return queryImageCheck(params);
		}
		if (uri.equals("reset-mail-templates")) {
			return resetMailTemplates();
		}
		if (uri.equals("scan-orphaned-files")) {
			return scanForOrphanedFiles(params);
		}
		return WebServer.notFound();
	}

	private static Response resetMailTemplates() {
		DbConfiguration.updateMailTemplates(true);
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", "OK");
	}

	/**
	 * Scan the vmstore for orphaned files and images, return a list.
	 * If POST param 'action' is 'delete', all those files will be deleted.
	 */
	private static Response scanForOrphanedFiles(Map<String, String> params) {
		if (!FileSystem.isStorageMounted())
			return WebServer.internalServerError("VMstore not mounted");
		final Map<String, DeleteResult> orphanedFiles = new HashMap<>();
		final String baseDir = Configuration.getVmStoreBasePath().toString();
		final int baseLen = baseDir.length() + (baseDir.endsWith("/") ? 0 : 1);
		final boolean del = params.containsKey("action") && params.get("action").equals("delete");
		final Set<String> known; // These we want to keep
		try {
			known = DbImage.getAllFilenames();
		} catch (SQLException e1) {
			return WebServer.internalServerError("Cannot query list of known images from database");
		}
		if (known.isEmpty()) {
			return WebServer.internalServerError("SAFTY CHECK: Known image list empty, aborting");
		}
		AtomicInteger matches = new AtomicInteger();
		try {
			// Consider only regular files, call checkFile for each one
			Files.find(Configuration.getVmStoreProdPath().toPath(), 8,
					(filePath, fileAttr) -> fileAttr.isRegularFile())
					.forEach((fileName) -> checkFile(fileName, orphanedFiles, baseLen, known, matches));
		} catch (IOException e) {
			return WebServer.internalServerError(e.toString());
		}
		if (del) {
			for (Entry<String, DeleteResult> it : orphanedFiles.entrySet()) {
				if (matches.get() == 0) {
					// Don't delete anything if the set of known files and the set of files we actually
					// found are disjoint
					it.setValue(DeleteResult.SAFETY_ABORT);
					continue;
				}
				Path filePath = Paths.get(baseDir + "/" + it.getKey());
				try {
					Files.delete(filePath);
					it.setValue(DeleteResult.DELETED);
				} catch (Exception e) {
					LOGGER.warn("Cannot delete " + filePath, e);
					it.setValue(DeleteResult.ERROR);
				}
			}
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8",
				Json.serialize(orphanedFiles));
	}

	private static enum DeleteResult {
		EXISTS,
		DELETED,
		ERROR,
		SAFETY_ABORT;
	}

	/**
	 * Function called for each file found on the VMstore to determine if it's
	 * orphaned.
	 *
	 * @param filePath File to check
	 * @param result Map to add the check result to
	 * @param baseLen length of the base path we need to strip from the absolute
	 *            path
	 * @param known list of known images from the db
	 * @param matches counter for files that match a DB entry
	 */
	private static void checkFile(Path filePath, Map<String, DeleteResult> result, int baseLen,
			Set<String> known, AtomicInteger matches) {
		if (filePath.endsWith("dozmod.lock"))
			return;
		final String relativeFileName;
		try {
			relativeFileName = filePath.toAbsolutePath().toString().substring(baseLen);
		} catch (IndexOutOfBoundsException e) {
			LOGGER.warn("Cannot make image path relative", e);
			return;
		}
		// Handle special dnbd3 files
		String compareFileName;
		if (relativeFileName.endsWith(".crc") || relativeFileName.endsWith(".map")) {
			compareFileName = relativeFileName.substring(0, relativeFileName.length() - 4);
		} else if (relativeFileName.endsWith(".meta")) {
			compareFileName = relativeFileName.substring(0, relativeFileName.length() - 5);
		} else {
			compareFileName = relativeFileName;
		}
		if (known.contains(compareFileName)) {
			matches.incrementAndGet();
		} else {
			result.put(relativeFileName, DeleteResult.EXISTS);
		}
	}

	private static Response checkImage(Map<String, String> params) {
		String versionId = params.get("versionid");
		if (versionId == null)
			return WebServer.badRequest("Missing versionid param");
		versionId = versionId.toLowerCase();
		boolean checkHashes = Boolean.valueOf(params.get("hash"));
		boolean updateState = Boolean.valueOf(params.get("update"));
		SubmitResult res = ImageValidCheck.check(versionId, checkHashes, updateState);
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", res.name());
	}

	private static Response queryImageCheck(Map<String, String> params) {
		String versionId = params.get("versionid");
		Map<String, CheckResult> result;
		if (versionId == null) {
			result = ImageValidCheck.getAll();
		} else {
			versionId = versionId.toLowerCase();
			CheckResult res = ImageValidCheck.getStatus(versionId);
			result = new HashMap<>();
			result.put(versionId, res);
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8",
				Json.serialize(result));
	}

	/**
	 * Delete all image versions marked as WANT_DELETE.
	 */
	private static Response deleteImages() {
		StringBuilder res = DeleteOldImages.hardDeleteImages();
		if (res == null)
			return WebServer.internalServerError();
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8",
				res.toString());
	}

	/**
	 * Send test mail to given SMTP config.
	 */
	private static Response mailTest(Map<String, String> params) {
		SmtpMailer smtpc;
		String recipient = params.get("recipient");
		String host = params.get("host");
		String senderAddress = params.get("senderAddress");
		String serverName = params.get("serverName");
		String replyTo = params.get("replyTo");
		String username = params.get("username");
		String password = params.get("password");
		int port = Util.parseInt(params.get("port"), 0);
		EncryptionMode ssl;
		try {
			ssl = EncryptionMode.valueOf(params.get("ssl"));
		} catch (Exception e) {
			return WebServer.badRequest("Invalid SSL mode '" + params.get("ssl") + "'");
		}
		// Validate
		if (port < 1 || port > 65535)
			return WebServer.badRequest("Invalid port");
		if (recipient == null)
			return WebServer.badRequest("Missing recipient");
		if (host == null)
			return WebServer.badRequest("Missing host");
		if (senderAddress == null)
			return WebServer.badRequest("Missing senderAddress");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			smtpc = new SmtpMailer(host, port, ssl, senderAddress, serverName, replyTo, username, password,
					new PrintStream(baos));
		} catch (InvalidKeyException | LoginException | NoSuchAlgorithmException | InvalidKeySpecException
				| IOException e) {
			try {
				baos.write("Could not connect to mail server".getBytes(StandardCharsets.UTF_8));
				e.printStackTrace(new PrintWriter(baos));
			} catch (IOException e1) {
			}
			smtpc = null;
		}

		boolean ret = false;
		if (smtpc != null) {
			MailTemplate template = DbConfiguration.getMailTemplate(Template.TEST_MAIL);
			Map<String, String> templateArgs = new HashMap<>();
			templateArgs.put("host", host);
			templateArgs.put("port", String.valueOf(port));
			templateArgs.put("ssl", ssl.toString());
			templateArgs.put("username", username);

			String msg = template.format(templateArgs);

			ret = smtpc.send(recipient, "bwLehrpool Mail Test", msg, "<sat.bwlehrpool.de>");
		}
		try {
			baos.write(("\n\n-----------------------------------------\nTestergebnis: "
					+ (ret ? "" : "nicht ") + "erfolgreich").getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8",
				new ByteArrayInputStream(baos.toByteArray()));
	}
}
