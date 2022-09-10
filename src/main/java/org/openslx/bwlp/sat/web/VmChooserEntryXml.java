package org.openslx.bwlp.sat.web;

import java.util.List;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

@Root(name = "eintrag")
public class VmChooserEntryXml {

	@Element
	private VmChooserParamXml image_name;
	@Element
	private VmChooserParamXml priority;
	@Element
	private VmChooserParamXml creator;
	@Element
	private VmChooserParamXml short_description;
	@Element
	private VmChooserParamXml long_description;
	@Element
	private VmChooserParamXml uuid;
	@Element
	private VmChooserParamXml virtualmachine;
	@Element
	private VmChooserParamXml os;
	@Element
	private VmChooserParamXml icon;
	@Element
	private VmChooserParamXml virtualizer_name;
	@Element
	private VmChooserParamXml os_name;
	@Element
	private VmChooserParamXml for_location;
	@Element
	private VmChooserParamXml is_template;
	@Element
	private VmChooserListXml filters;

	public VmChooserEntryXml(String imageFilePath, int priority, String creator, String short_description,
			String long_description, String uuid, String virtId, String virtualizerName, String osVirtName,
			String osDisplayName, String icon, boolean isForThisLocation, boolean isTemplate, List<XmlFilterEntry> ldapFilters) {
		this.image_name = new VmChooserParamXml(imageFilePath);
		this.priority = new VmChooserParamXml(priority);
		this.creator = new VmChooserParamXml(creator);
		this.short_description = new VmChooserParamXml(short_description);
		this.long_description = new VmChooserParamXml(long_description);
		this.uuid = new VmChooserParamXml(uuid);
		this.virtualmachine = new VmChooserParamXml(virtId);
		this.os = new VmChooserParamXml(osVirtName);
		this.icon = new VmChooserParamXml(icon);
		this.virtualizer_name = new VmChooserParamXml(virtualizerName);
		this.os_name = new VmChooserParamXml(osDisplayName);
		this.for_location = new VmChooserParamXml(isForThisLocation);
		this.is_template = new VmChooserParamXml(isTemplate);
		this.filters = new VmChooserListXml(ldapFilters);
	}

	private static class VmChooserParamXml {

		@Attribute(required = false)
		private String param;

		public VmChooserParamXml(String value) {
			this.param = value;
		}

		public VmChooserParamXml(boolean bool) {
			this.param = bool ? "1" : "0";
		}

		public VmChooserParamXml(int val) {
			this.param = Integer.toString(val);
		}

	}
	
	private static class VmChooserListXml {
		
		@ElementList(required = false, inline = true, entry = "filter")
		private List<XmlFilterEntry> list;
		
		public VmChooserListXml(List<XmlFilterEntry> list) {
			this.list = list;
		}
		
	}

}
