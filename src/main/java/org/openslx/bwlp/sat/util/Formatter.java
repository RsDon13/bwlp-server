package org.openslx.bwlp.sat.util;

import java.io.File;
import java.text.Normalizer;
import java.util.UUID;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.openslx.bwlp.thrift.iface.UserInfo;

public class Formatter {

	private static final DateTimeFormatter vmNameDateFormat = DateTimeFormat.forPattern("dd_HH-mm-ss");

	private static final DateTimeFormatter displayDateFormat = DateTimeFormat.forPattern("dd.MM.yy HH:mm");

	/**
	 * Generate a unique file name used for a virtual machine
	 * image that is currently uploading.
	 * 
	 * @return Absolute path name of file
	 */
	public static File getTempImageName() {
		return new File(Configuration.getCurrentVmStorePath(), UUID.randomUUID().toString()
				+ Constants.INCOMPLETE_UPLOAD_SUFFIX);
	}

	/**
	 * Generate a file name for the given VM based on owner and display name.
	 * 
	 * @param ts Timestamp of upload
	 * @param user The user associated with the VM, e.g. the owner
	 * @param imageName Name of the VM
	 * @param ext
	 * @return File name for the VM derived from the function's input
	 */
	public static String vmName(long ts, UserInfo user, String imageName, String ext) {
		return cleanFileName(vmNameDateFormat.print(ts) + "_" + user.lastName + "_"
				+ imageName + "." + ext).toLowerCase();
	}

	/**
	 * Make sure file name contains only a subset of ascii characters and is not
	 * too long.
	 * 
	 * @param name What we want to turn into a file name
	 * @return A sanitized form of name that should be safe to use as a file
	 *         name
	 */
	public static String cleanFileName(String name) {
		if (name == null)
			return "null";
		name = Normalizer.normalize(name, Normalizer.Form.NFD);
		name = name.replaceAll("[^a-zA-Z0-9_\\.\\-]+", "_");
		if (name.length() > 120)
			name = name.substring(0, 120);
		return name;
	}

	public static String userFullName(UserInfo ui) {
		if (ui == null)
			return "null";
		return ui.firstName + " " + ui.lastName;
	}

	/**
	 * Format a unix time stamp as dd.MM.yy HH:mm
	 * 
	 * @param unixTime seconds since 01.01.1970 UTC
	 * @return dd.MM.yy HH:mm
	 */
	public static String date(long unixTime) {
		if (unixTime == 0)
			return "???";
		return displayDateFormat.print(unixTime * 1000);
	}
}
