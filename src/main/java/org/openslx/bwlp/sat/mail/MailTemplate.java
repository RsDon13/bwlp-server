package org.openslx.bwlp.sat.mail;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * stores a mail template and offers methods to place variables
 **/
public class MailTemplate {

	private ArrayList<String> snippets = new ArrayList<>();
	private ArrayList<String> identifiers = new ArrayList<>();
	private String raw;

	public MailTemplate(String raw_template) {
		this.raw = raw_template;
		this.parseTemplate(raw_template);
	}

	public String format(Map<String, String> vars) {
		Iterator<String> it_snippets = snippets.iterator();
		Iterator<String> it_identifiers = identifiers.iterator();

		StringBuilder sb = new StringBuilder();

		boolean progress;
		do {
			progress = false;
			if (it_snippets.hasNext()) {
				sb.append(it_snippets.next());
				progress = true;
			}
			if (it_identifiers.hasNext()) {
				sb.append(vars.get(it_identifiers.next()));
				progress = true;
			}
		} while (progress);

		return sb.toString();
	}

	private boolean validVarChar(char c) {
		if (c >= 'a' && c <= 'z')
			return true;
		if (c >= 'A' && c <= 'Z')
			return true;
		if (c >= '0' && c <= '9')
			return true;
		return c == '_';
	}

	/**
	 * read the raw string from % to % and the fragments either into snippets or
	 * variables. Valid variable names are surrounded are /%[a-zA-Z0-9_]+%/
	 * The trailing % might be missing. The name simply ends at the first
	 * character that is not valid for variable names. So the regexp is actually
	 * /%[a-zA-Z0-9_]+%?/
	 * Why? Compatibility with an old release that had a borked default template
	 * :(
	 */
	private void parseTemplate(String raw) {
		int i = 0;
		while (true) {
			final int len = raw.length();
			int begin = raw.indexOf("%", i);
			int end = begin + 1;
			if (begin != -1) {
				while (end < len && validVarChar(raw.charAt(end))) {
					end++;
				}
			}

			if (begin == -1 || end >= len) {
				/* no variable anymore, so just add a snippet until the end */
				/* OR: stray %, add rest as literal text */
				String snippet = raw.substring(i);
				this.snippets.add(snippet);
				break;
			}
			String snippet = raw.substring(i, begin);
			String identifier = raw.substring(begin + 1, end);

			this.snippets.add(snippet);
			this.identifiers.add(identifier);

			if (raw.charAt(end) == '%') {
				i = end + 1;
			} else {
				i = end;
			}
		}
	}

	public String getRaw() {
		return this.raw;
	}

}
