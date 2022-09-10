package org.openslx.bwlp.sat.mail;

import java.util.HashMap;

import org.openslx.bwlp.sat.mail.MailTemplatePlain.Template;

/**
 * used for serialization to the database
 * 
 * @author klingerc
 *
 */
public class MailTemplateConfiguration {

		
	private static final MailTemplatePlain[] defaultTemplates = {
			new MailTemplatePlain(
					Template.LECTURE_UPDATED,
					"Wird an die Verantwortlichen einer Veranstaltung gesendet, wenn die verknüpfte VM aktualisiert wurde.",
					"Die zur Veranstaltung '%lecture%' gehörige VM wurde aktualisiert.",
					new String[]{"image", "created", "uploader"},
					new String[]{"lecture"},
					1
					),
			
			new MailTemplatePlain(
					Template.LECTURE_DEACTIVATED,
					"Wird versendet, wenn eine Veranstaltung unerwartet deaktiviert werden musste.",
					"Die Veranstaltung '%lecture%' musste deaktiviert werden,"
						+ " da die verknüpfte VM '%image%' gelöscht oder beschädigt wurde. Bitte überprüfen"
						+ " Sie die Veranstaltung und ändern Sie ggf. die Verlinkung,"
						+ " damit die Veranstaltung wieder verwendbar ist.",
					new String[] {"image"},
					new String[] {"lecture"},
					1
					),
			
			new MailTemplatePlain(
					Template.VM_CURRENT_VERSION_EXPIRING,
					"Wird versendet, wenn die aktuellste Version einer VM kurz vor dem Ablaufdatum steht.",
					"Die aktuellste Version der VM '%image%' läuft in %remaining_days% Tag(en) ab."
						+ " Bitte aktualisieren Sie die VM, da verknüpfte Veranstaltungen sonst deaktiviert werden.",
					new String[]{"remaining_days", "created", "image_expiretime"},
					new String[]{"image"},
					0
					),
			
			new MailTemplatePlain(
					Template.VM_OLD_VERSION_EXPIRING,
					"Hinweis, dass eine alte Version einer VM abläuft.",
					"Eine alte Version der VM '%image%' läuft in %remaining_days% Tag(en) ab (Version vom %created%)."
						+ " Eine aktuellere Version ist vorhanden, diese Nachricht dient nur der Information.",
					new String[]{"remaining_days", "created", "image_expiretime"},
					new String[]{"image"},
					0
					),
				
			new MailTemplatePlain(
					Template.LECTURE_LINKED_VM_EXPIRING,
					"Hinweis, dass die zu einer Veranstaltung gehörige VM bald abläuft.",
					"Hinweis zur Veranstaltung '%lecture%': Die verwendete VM '%image%'"
						+ " läuft in %remaining_days% Tag(en) ab. Bitte aktualisieren oder wechseln Sie die VM.",
					new String[]{"remaining_days", "image_expiretime"},
					new String[]{"lecture", "image"},
					1
					),
					
			new MailTemplatePlain(
					Template.LECTURE_EXPIRING,
					"Wird versendet, wenn eine Veranstaltung kurz vor dem Enddatum steht.",
					"Die Veranstaltung '%lecture%' läuft in %remaining_days% Tag(en) ab. Verlängern Sie bei Bedarf das Ablaufdatum.",
					new String[]{"remaining_days", "lecture_endtime"},
					new String[]{"lecture"},
					0
					),
			
			new MailTemplatePlain(
					Template.VM_DELETED_LAST_VERSION,
					"Wird versendet, wenn die letzte gültige Version einer VM gelöscht wurde."
						+ " Die Metadaten der VM bleiben für einige Tage erhalten, falls die Verantwortliche"
						+ " eine neue Version hochladen möchte, ohne die Metadaten erneut eingeben zu müssen.",
						"Die letzte verbliebene Version der VM '%image%' wurde gelöscht. Die Metadaten der VM wurden zur Löschung vorgemerkt.",					
					new String[]{},
					new String[]{"image"},
					0
					),
			
			new MailTemplatePlain(
					Template.VM_DELETED_OLD_VERSION,
					"Bestätigung dass eine alte Version der VM gelöscht wurde",
					"Eine alte Version der VM '%image%' vom %old_created% wurde gelöscht\n"
						+ "Die neueste Version ist jetzt vom %new_created% (erstellt von %uploader%)",
					new String[]{"old_created", "new_created", "uploader"},
					new String[]{"image"},
					0
					),
			
			new MailTemplatePlain(
					Template.LECTURE_FORCED_UPDATE,
					"Wird versendet, wenn die VM zu einer Veranstaltung unerwartet nicht mehr verfügbar"
						+ " ist, aber eine neuere oder ältere Version der VM als Ausweichmöglichkeit"
						+ " gewählt werden konnte.",
					"Die verlinkte VM zur Veranstaltung '%lecture%' wurde gelöscht oder ist beschädigt."
						+ " Daher verweist sie jetzt auf die VM-Version vom %created%."
						+ " Bitte überprüfen Sie ggf., ob diese VM-Version für Ihren Kurs geeignet ist.",
					new String[]{"created"},
					new String[]{"lecture"},
					1
					),
			
			new MailTemplatePlain(
					Template.TEST_MAIL,
					"Die Test-Email, die in der Mail-Konfiguration verschickt werden kann.",
					"Test der Mailkonfiguration.\n\n%host%:%port% \nSSL: %ssl%"
						+ "\nLogin: %username%",
					new String[]{"host", "port", "ssl", "username"},
					new String[]{},
					0
					),
			
			new MailTemplatePlain(
					Template.GENERAL_WRAPPER,
					"Einleitung und Grußzeile ausgehender Mails.",
					"Guten Tag, %first_name% %last_name%,\n\n"
						+ "Bitte beachten Sie folgende Hinweise zu Virtuellen Maschinen und Veranstaltungen,\n"
						+ "für die Sie als zuständige Person hinterlegt sind:\n\n"
						+ "%messages%"
						+ "\n\n"
						+ "Dies ist eine automatisch generierte Mail. Wenn Sie keine Hinweise dieser Art\n"
						+ "wünschen, melden Sie sich bitte mittels der bwLehrpool-Suite an diesem\n"
						+ "Satellitenserver an und deaktivieren Sie in den Einstellungen die\n"
						+ "e-Mail-Benachrichtigungen."
						+ "\n\n-- \n" + "Generiert auf %sender_name%",
					new String[]{"first_name", "last_name", "sender_name"},
					new String[]{"messages"},
					0
					)
			
			};
	
	
	public static final MailTemplateConfiguration defaultTemplateConfiguration = new MailTemplateConfiguration(defaultTemplates);
	

	
	private MailTemplatePlain[] templates;

	/**
	 * 
	 * @param name the name of the desired mail template
	 * @return the mail template or NULL if no such template exists
	 */
	public MailTemplate getByName(Template name) {
		for(int i = 0; i < templates.length; i++) {
			if (templates[i].getName() == name) {
				return templates[i].toMailTemplate();
			}
		}
		return null;
	}
	
	public MailTemplateConfiguration(MailTemplatePlain[] templates) {
		this.templates = templates;
	}
	
	/**
	 * 
	 * @param newconf the configuration that will be merged
	 * @return a new configuration containing templates from "this" and conf.
	 * If a template with the same name exists in both then "this" has priority
	 */
	public MailTemplateConfiguration merge(MailTemplateConfiguration newconf) {		
		HashMap<Template, MailTemplatePlain> templates = new HashMap<>();
				
		/* add all templates from here */
		for (MailTemplatePlain t : this.templates) {
			if (t.getName() != null) {
				templates.put(t.getName(), t);
			}
		}
		
		/* add all templates from conf that don't exist yet */
		for (MailTemplatePlain t : newconf.templates) {
			if (t.getName() == null)
				continue;
			if (!templates.containsKey(t.getName())) {
				templates.put(t.getName(), t);
			} else {
				templates.get(t.getName()).mergeWithUpdatedVersion(t);
			}
		}
		
		/* convert to array */
		MailTemplatePlain[] templatesArray = new MailTemplatePlain[templates.size()];		
		templates.values().toArray(templatesArray);
		
		return new MailTemplateConfiguration(templatesArray);
	}

	public int size() {
		return this.templates.length;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[");
		for (MailTemplatePlain p : this.templates) {
			sb.append(p.getName());
			sb.append(" ");
		}
		sb.append("]"); 
		return sb.toString();
	}
}
