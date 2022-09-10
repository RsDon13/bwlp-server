package org.openslx.bwlp.sat.web;

import java.util.ArrayList;
import java.util.List;

import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

@Root(name = "settings")
public class VmChooserListXml {

	@ElementList(inline = true, name = "eintrag")
	private List<VmChooserEntryXml> entries;

	public VmChooserListXml(boolean createEmptyList) {
		if (createEmptyList) {
			entries = new ArrayList<>();
		}
	}

	public void add(VmChooserEntryXml vmChooserEntryXml) {
		entries.add(vmChooserEntryXml);
	}

}
