package org.openslx.bwlp.sat.mail;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.bwlp.sat.database.mappers.DbMailQueue;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.database.mappers.DbUser.User;
import org.openslx.bwlp.sat.mail.MailTemplatePlain.Template;
import org.openslx.bwlp.sat.mail.SmtpMailer.EncryptionMode;
import org.openslx.bwlp.sat.maintenance.MailFlusher;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.Util;

public class MailQueue {

	public static class MailConfig {
		public String host;
		public int port;
		public EncryptionMode ssl;
		public String senderAddress;
		public String replyTo;
		public String password;
		public String username;
		public String serverName;
	}

	private static final Logger LOGGER = LogManager.getLogger(MailQueue.class);

	private static final int BATCH_SIZE = 25;

	private static boolean busy = false;

	/**
	 * Convenience wrapper for {@link DbMailQueue#queue(Mail)}, swallowing any
	 * {@link SQLException}, so the mailing will keep going (or try to at least)
	 * 
	 * @param mail Mail to queue for sending
	 */
	public static void queue(Mail mail) {
		try {
			DbMailQueue.queue(mail);
		} catch (SQLException e) {
		}
	}

	/**
	 * Send queued messages. This operation is rate-limited. In case there might
	 * be more messages to send, this function will return after sending some of
	 * them, and schedule a maintenance job that will trigger this method again.
	 * 
	 * @throws InterruptedException
	 */
	public static void flush() throws InterruptedException {
		synchronized (MailQueue.class) {
			if (busy) // Will run again when the scheduler decides to
				return;
			busy = true;
		}
		try {
			List<Mail> queuedMails;
			try {
				queuedMails = DbMailQueue.getQueued(BATCH_SIZE);
			} catch (SQLException e) {
				LOGGER.error("Cannot retrieve queued mails from DB");
				return;
			}
			// Anything to do?
			if (queuedMails.isEmpty())
				return;
			// Fetch SMTP config
			MailConfig conf;
			try {
				conf = DbConfiguration.getMailConfig();
			} catch (SQLException e) {
				conf = null;
				return;
			}
			if (!MailGenerator.isValidMailConfig(conf)) {
				LOGGER.error("Cannot send mail with no mail config");
				return;
			}
			// Setup mailer
			SmtpMailer smtpc;
			try {
				smtpc = new SmtpMailer(conf.host, conf.port, conf.ssl, conf.senderAddress, conf.serverName,
						conf.replyTo, conf.username, conf.password, null);
			} catch (InvalidKeyException | LoginException | NoSuchAlgorithmException
					| InvalidKeySpecException | IOException e) {
				LOGGER.error("Could not initialize connection to SMTP server. Mails will not be sent", e);
				return;
			}
			// Iterate over mail: Group by receiving user
			Map<String, List<Mail>> batch = new HashMap<>();
			for (Mail mail : queuedMails) {
				List<Mail> list = batch.get(mail.userId);
				if (list == null) {
					list = new ArrayList<>();
					batch.put(mail.userId, list);
				}
				list.add(mail);
			}
			// Send all the mails
			int delaySeconds = 2;
			boolean sendOk = true;
			for (List<Mail> userBatch : batch.values()) {
				if (userBatch.isEmpty()) {
					continue; // Now how the hell did that happen?
				}
				User cachedUser;
				try {
					cachedUser = DbUser.getCached(userBatch.get(0).userId);
				} catch (TNotFoundException | SQLException e) {
					LOGGER.warn("Cannot get user for id " + userBatch.get(0).userId
							+ ": Sending mails failed.");
					try {
						DbMailQueue.markFailed(userBatch);
					} catch (SQLException e1) {
					}
					continue;
				}
				// Double-check if user wants mail (unlikely, but user might just have changed the setting)
				if (!cachedUser.local.emailNotifications) {
					try {
						DbMailQueue.markSent(userBatch);
					} catch (SQLException e) {
					}
					continue;
				}
				StringBuilder sb = new StringBuilder();
				for (Mail mail : userBatch) {
					if (sb.length() != 0) {
						sb.append('\n');
					}
					sb.append("* ");
					sb.append(mail.message);
				}
				sendOk = sendMail(conf, smtpc, cachedUser.ui, sb.toString());
				LOGGER.debug("Sending mail to " + cachedUser.ui.eMail + ": "
						+ (sendOk ? "success" : "failure"));
				try {
					if (sendOk) {
						DbMailQueue.markSent(userBatch);
					} else {
						DbMailQueue.markFailed(userBatch);
					}
				} catch (SQLException e) {
				}
				Thread.sleep(delaySeconds * 1000);
				delaySeconds += 1;
			}
			smtpc.close();
			// We got a full batch from DB, call flush() again in a minute
			if (queuedMails.size() == BATCH_SIZE && sendOk) {
				callAgainInOneMinute();
			}
		} finally {
			synchronized (MailQueue.class) {
				busy = false;
			}
		}
	}

	private static void callAgainInOneMinute() {
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				MailFlusher.start();
			}
		}, TimeUnit.MINUTES.toMillis(1));
	}

	private static boolean sendMail(MailConfig conf, SmtpMailer smtpc, UserInfo user, final String message) {
		MailTemplate wrapper = DbConfiguration.getMailTemplate(Template.GENERAL_WRAPPER);
		Map<String, String> templateArgs = new HashMap<>();
		templateArgs.put("first_name", user.firstName);
		templateArgs.put("last_name", user.lastName);
		templateArgs.put("sender_name", conf.serverName);
		templateArgs.put("messages", message);
		String fullMessage = wrapper.format(templateArgs);
		if (!Util.isEmptyString(conf.replyTo)) {
			fullMessage += "\n\nBei weiteren Fragen wenden Sie sich bitte an den Support unter\n"
					+ conf.replyTo;
		}
		if (fullMessage.contains("\r\n")) {
			fullMessage = fullMessage.replace("\r\n", "\n");
		}
		if (fullMessage.contains("\n")) {
			fullMessage = fullMessage.replace("\n", "\r\n");
		}
		return smtpc.send(user.eMail, "[bwLehrpool] Hinweise zu Ihren VMs/Veranstaltungen", fullMessage,
				"<sat.bwlehrpool.de>");
	}

}
