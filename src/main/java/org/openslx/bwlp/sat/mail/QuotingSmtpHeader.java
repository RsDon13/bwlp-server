package org.openslx.bwlp.sat.mail;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.net.smtp.SimpleSMTPHeader;

public class QuotingSmtpHeader extends SimpleSMTPHeader {

	private static CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();

	public QuotingSmtpHeader(String fromAddress, String fromDisplayName, String to, String subject) {
		super(buildNamedAddress(fromAddress, fromDisplayName), to, wrapEncoding(subject));
	}

	public QuotingSmtpHeader(String from, String to, String subject) {
		super(from, to, wrapEncoding(subject));
	}

	@Override
	public void addHeaderField(String headerField, String value) {
		super.addHeaderField(headerField, wrapEncoding(value));
	}

	@Override
	public void addCC(String address) {
		super.addCC(wrapEncoding(address));
	}

	private static String wrapEncoding(String input) {
		return wrapEncoding(input, false);
	}

	private static String wrapEncoding(String input, boolean addQuotesIfSpaces) {
		boolean isAscii;
		synchronized (asciiEncoder) { // Has class-wide state vars
			isAscii = asciiEncoder.canEncode(input);
		}
		if (isAscii) {
			if (addQuotesIfSpaces && (input.contains(" ") || input.contains("\t")))
				return "\"" + input + "\"";
			return input;
		}
		return "=?utf-8?B?"
				+ new String(Base64.encodeBase64(input.getBytes(StandardCharsets.UTF_8), false),
						StandardCharsets.UTF_8) + "?=";
	}

	private static String buildNamedAddress(String address, String name) {
		if (name == null)
			return address;
		return wrapEncoding(name, true) + " <" + address + ">";
	}

}
