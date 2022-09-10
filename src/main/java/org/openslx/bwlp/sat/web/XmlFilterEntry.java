package org.openslx.bwlp.sat.web;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

public class XmlFilterEntry {

	@Attribute(required = false)
	private String type;
	@Element
	private String key;
	@Element
	private String value;

	public XmlFilterEntry(String type, String key, String value) {
		this.type = type;
		this.key = key;
		this.value = value;
	}

}
