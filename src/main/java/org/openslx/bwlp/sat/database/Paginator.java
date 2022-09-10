package org.openslx.bwlp.sat.database;

public class Paginator {

	public static final int PER_PAGE = 200;

	public static String limitStatement(int page) {
		return " LIMIT " + (page * PER_PAGE) + ", " + PER_PAGE;
	}

}
