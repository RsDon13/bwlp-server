package org.openslx.bwlp.sat.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.codec.binary.Hex;
import org.openslx.bwlp.thrift.iface.UserInfo;

public class Mail {

	private static final MessageDigest md;

	static {
		MessageDigest tmp;
		try {
			tmp = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			tmp = null;
		}
		md = tmp;
	}

	public final String id;
	public final String userId;
	public final String message;

	public Mail(String id, String userId, String message) {
		this.id = id;
		this.userId = userId;
		this.message = message;
	}

	public Mail(UserInfo recipient, String message) {
		this(hash(recipient, message), recipient.userId, message);
	}

	private static String hash(UserInfo recipient, String message) {
		synchronized (md) {
			md.update(recipient.userId.getBytes(StandardCharsets.UTF_8));
			md.update(message.getBytes(StandardCharsets.UTF_8));
			return Hex.encodeHexString(md.digest());
		}
	}

}
