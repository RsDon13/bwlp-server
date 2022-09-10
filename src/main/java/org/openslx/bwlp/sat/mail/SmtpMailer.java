package org.openslx.bwlp.sat.mail;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import javax.security.auth.login.LoginException;

import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.smtp.AuthenticatingSMTPClient;
import org.apache.commons.net.smtp.AuthenticatingSMTPClient.AUTH_METHOD;
import org.apache.commons.net.smtp.SMTPReply;
import org.apache.commons.net.smtp.SimpleSMTPHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.util.Util;

public class SmtpMailer {

	// TODO Logging
	private static final Logger LOGGER = LogManager.getLogger(SmtpMailer.class);

	public enum EncryptionMode {
		NONE,
		IMPLICIT,
		EXPLICIT
	}

	private final String fromAddress;
	private final String fromName;
	private final String replyTo;
	private final AuthenticatingSMTPClient client;

	public SmtpMailer(String host, int port, EncryptionMode ssl, String fromAddress, String fromName,
			String replyTo, String username, String password, PrintStream logStream)
			throws UnknownHostException, SocketException, IOException, LoginException, InvalidKeyException,
			NoSuchAlgorithmException, InvalidKeySpecException {
		InetAddress[] ips = InetAddress.getAllByName(host);
		if (ips == null || ips.length == 0)
			throw new UnknownHostException(host);
		LOGGER.debug("Mailing via " + host + ", " + ssl);
		if (ssl == EncryptionMode.EXPLICIT || ssl == EncryptionMode.NONE) {
			client = new AuthenticatingSMTPClient("TLSv1.2", false, "UTF-8");
		} else {
			client = new AuthenticatingSMTPClient("TLSv1.2", true, "UTF-8");
		}
		boolean cleanup = true;
		try {
			if (logStream != null) {
				client.addProtocolCommandListener(new PrintCommandListener(logStream));
			}
			client.setConnectTimeout(5000);
			client.setDefaultTimeout(10000);
			IOException conEx = null;
			for (InetAddress ip : ips) {
				try {
					client.connect(ip, port);
					if (!SMTPReply.isPositiveCompletion(client.getReplyCode())) {
						client.disconnect();
						continue;
					}
					conEx = null;
					break;
				} catch (IOException e) {
					conEx = e;
				}
			}
			if (conEx != null)
				throw conEx;
			if (!client.elogin("bwlehrpool.sat")) {
				throw new LoginException("SMTP server rejected EHLO");
			}
			if (ssl == EncryptionMode.EXPLICIT) {
				if (!client.execTLS()) {
					throw new LoginException("STARTTLS (explicit TLS) failed");
				}
				// Not checking result of this. We SHOULD do this according to RFC2487, and didn't previously, which
				// worked fine for a long time. Now we stumbled upon a gateway that REQUIRES another EHLO after
				// STARTTLS. If for some reason this fails, it might still be a valid session from the first EHLO
				// I guess, so just try to keep going until something else breaks. :-/
				client.elogin("bwlehrpool.sat");
			}
			if (!Util.isEmptyString(username)) {
				boolean authed = false;
				try {
					authed = client.auth(AUTH_METHOD.CRAM_MD5, username, password);
				} catch (InvalidKeyException | NoSuchAlgorithmException | InvalidKeySpecException e) {
					e.printStackTrace();
				}
				if (!authed && !client.auth(AUTH_METHOD.PLAIN, username, password)) {
					throw new LoginException("Server rejected AUTH command. Invalid username or password?");
				}
			}
			cleanup = false;
			this.fromAddress = fromAddress;
			this.fromName = fromName;
			this.replyTo = replyTo;
		} finally {
			if (cleanup)
				cleanup();
		}
	}

	private void cleanup() {
		try {
			client.logout();
		} catch (Exception e) {
		}
		try {
			client.disconnect();
		} catch (Exception e) {
		}
	}

	private void abort() throws IOException {
		if (!client.reset())
			throw new IOException("Cannot abort current mail transaction");
	}

	public boolean send(String recipient, String subject, String message, String listId) {
		Writer writer;
		SimpleSMTPHeader header;

		try {
			header = new QuotingSmtpHeader(fromAddress, fromName, recipient, subject);
			if (!Util.isEmptyString(replyTo)) {
				header.addHeaderField("Reply-To", replyTo);
			}
			if (!Util.isEmptyString(listId)) {
				header.addHeaderField("List-Id", listId);
				header.addHeaderField("Precedence", "bulk");
				header.addHeaderField("Auto-Submitted", "auto-generated");
			}
			header.addHeaderField("Content-Type", "text/plain; charset=utf-8");
			header.addHeaderField("Content-Transfer-Encoding", "8bit");

			if (!client.setSender(fromAddress)) {
				abort();
				return false;
			}
			if (!client.addRecipient(recipient)) {
				abort();
				return false;
			}
			writer = client.sendMessageData();
			if (writer == null) {
				abort();
				return false;
			}

			writer.write(header.toString());
			writer.write(message);
			writer.close();
			client.completePendingCommand();

			return true;
		} catch (IOException e) {
			cleanup();
			return false;
		}
	}

	public boolean isConnected() {
		if (!client.isConnected())
			return false;
		try {
			client.sendNoOp();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public void close() {
		if (client.isConnected()) {
			try {
				client.logout();
			} catch (Exception e) { // Don't care
			}
			try {
				client.disconnect();
			} catch (Exception e) { // Don't care
			}
		}
	}

}
