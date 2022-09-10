package bwlehrpool;

import java.util.HashMap;

import org.openslx.bwlp.sat.mail.MailTemplate;

import junit.framework.TestCase;

public class MailTemplateTest extends TestCase {

	public void testMailTemplate() {
		/* variables */
		HashMap<String, String> map = new HashMap<>();
		map.put("eins", "1");
		map.put("zwei", "2");
		map.put("drei", "3");
		
		assertEquals("Hallo Eins 2 drei", new MailTemplate("Hallo Eins %zwei% drei").format(map));
		assertEquals("1", new MailTemplate("%eins%").format(map));
		assertEquals("x1", new MailTemplate("x%eins%").format(map));
		assertEquals("1x", new MailTemplate("%eins%x").format(map));
		assertEquals("123", new MailTemplate("%eins%%zwei%%drei%").format(map));
		assertEquals("123x11", new MailTemplate("%eins%%zwei%%drei%x%eins%1").format(map));
		assertEquals(".1.%zwei", new MailTemplate(".%eins%.%zwei").format(map));
		assertEquals(".1.2 3' und vier", new MailTemplate(".%eins%.%zwei %drei' und vier").format(map));
		assertEquals("1 null null zehn%", new MailTemplate("%eins% %acht% %neun zehn%").format(map));
	}

	public void testMailTemplateWrongPlaceholder() {
		MailTemplate template = new MailTemplate("hallo %zwei%");
		HashMap<String, String> map = new HashMap<>();
		map.put("eins", "eense");
		assertEquals("hallo null", template.format(map));
		
	}
	
}
