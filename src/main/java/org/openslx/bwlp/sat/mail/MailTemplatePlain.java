package org.openslx.bwlp.sat.mail;

import com.google.gson.annotations.SerializedName;

public class MailTemplatePlain {

	public enum Template {
		LECTURE_UPDATED,
		LECTURE_DEACTIVATED,
		VM_CURRENT_VERSION_EXPIRING,
		VM_OLD_VERSION_EXPIRING,
		LECTURE_LINKED_VM_EXPIRING,
		LECTURE_EXPIRING,
		VM_DELETED_LAST_VERSION,
		VM_DELETED_OLD_VERSION,
		LECTURE_FORCED_UPDATE,
		TEST_MAIL,
		GENERAL_WRAPPER
	}

	private Template name;
	private String description;
	private String template;

	@SerializedName("optional_variables")
	private String[] optionalVariables;

	@SerializedName("mandatory_variables")
	private String[] mandatoryVariables;
	
	private int version;
	
	@SerializedName("edit_version")
	private int editVersion;

	private boolean original = false;

	@SerializedName("original_template")
	private String original_template;

	public MailTemplatePlain(Template name, String description, String template, String[] optionalVariables,
			String[] mandatoryVariables, int version) {
		this.name = name;
		this.description = description;
		this.original_template = this.template = template;
		this.optionalVariables = optionalVariables;
		this.mandatoryVariables = mandatoryVariables;
		this.version = version;
		this.original = true;
	}

	public Template getName() {
		return this.name;
	}

	public MailTemplate toMailTemplate() {
		return new MailTemplate(template);
	}
	
	public void mergeWithUpdatedVersion(MailTemplatePlain updated) {
		this.description = updated.description;
		this.optionalVariables = updated.optionalVariables;
		this.mandatoryVariables = updated.mandatoryVariables;
		if (this.original || this.template.trim().replace("\r\n", "\n").equals(updated.template.trim().replace("\r\n", "\n"))) {
			// they are the same or it has not been edited
			this.original = true;
			this.template = updated.template;
			this.editVersion = this.version = updated.version;
			this.original_template = "";
		} else {
			// Just update what the latest version is
			this.version = updated.version;
			this.original_template = updated.template;
		}
	}

}
