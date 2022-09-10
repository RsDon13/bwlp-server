package org.openslx.bwlp.sat.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.bwlp.sat.database.mappers.DbLecture.LaunchData;
import org.openslx.bwlp.sat.database.mappers.DbLecture.RunScript;
import org.openslx.bwlp.sat.fileserv.FileServer;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.thrift.iface.NetRule;
import org.openslx.bwlp.thrift.iface.NetShare;
import org.openslx.bwlp.thrift.iface.NetShareAuth;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.util.GrowingThreadPoolExecutor;
import org.openslx.util.Json;
import org.openslx.util.Util;
import org.openslx.util.TarArchiveUtil.TarArchiveWriter;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

	private static final Logger LOGGER = LogManager.getLogger(WebServer.class);

	private static final ThreadPoolExecutor tpe = new GrowingThreadPoolExecutor(1, 8, 1, TimeUnit.MINUTES,
			new LinkedBlockingQueue<Runnable>(16));

	private static final Serializer serializer = new Persister();

	public WebServer(int port) {
		super(Configuration.getWebServerBindAddressLocal(), port);
		super.maxRequestSize = 65535;
	}

	@Override
	public Response serve(IHTTPSession session) {
		String uri = session.getUri();

		if (uri == null || uri.length() == 0) {
			return internalServerError();
		}

		// Sanitize
		if (uri.contains("//")) {
			uri = uri.replaceAll("//+", "/");
		}

		try {
			return handle(session, uri);
		} catch (Throwable t) {
			LOGGER.debug("Could not handle request", t);
			return internalServerError();
		}
	}

	private Response handle(IHTTPSession session, String uri) {
		// Our special stuff
		String[] parts = uri.replaceFirst("^/+", "").split("/+");
		// /vmchooser/*
		if (parts.length > 1 && parts[0].equals("vmchooser")) {
			if (parts[1].equals("list")) {
				try {
					return serveVmChooserList(session.getParms());
				} catch (Exception e) {
					LOGGER.debug("problem while retrieving the vmChooserList", e);
					return internalServerError();
				}
			}
			if (parts[1].equals("lecture")) {
				if (parts.length < 4)
					return badRequest("Bad Request");
				if (parts[3].equals("metadata"))
					return serveMetaData(parts[2]);
				if (parts[3].equals("netrules"))
					return serveLectureNetRules(parts[2]);
				if (parts[3].equals("imagemeta"))
					return serveContainerImageMetaData(parts[2]);
			}
			return notFound();
		}
		if (uri.startsWith("/bwlp/container/clusterimages")) {
			return serverContainerImages();
		}
		if (uri.startsWith("/image/container/")) {
			if (parts.length < 4)
				return badRequest("Bad Request");
			if (parts[3].equals("metadata"))
				return serveContainerImageMetaData(parts[2]);
		}

		if (uri.startsWith("/status/fileserver")) {
			return serveStatus();
		}
		if (session.getMethod() == Method.POST && uri.startsWith("/do/")) {
			try {
				session.parseBody(null);
			} catch (IOException | ResponseException e) {
				LOGGER.debug("could not parse request body", e);
				return internalServerError();
			}
			return WebRpc.handle(uri.substring(4), session.getParms());
		}

		return notFound();
	}

	private Response serveStatus() {
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8",
				Json.serialize(FileServer.instance().getStatus()));
	}

	/**
	 * Return meta data (eg. *.vmx) required to start the given lecture.
	 * 
	 * @param lectureId
	 * @return
	 */
	private Response serveMetaData(final String lectureId) {
		PipedInputStream sink = new PipedInputStream(10000);
		try {
			final TarArchiveWriter tarArchiveWriter = new TarArchiveWriter(new PipedOutputStream(sink));
			final LaunchData ld;
			try {
				ld = DbLecture.getClientLaunchData(lectureId);
			} catch (TNotFoundException e) {
				// TODO better virt error handling
				return notFound();
			} catch (SQLException e) {
				return internalServerError();
			}
			// Meta is required, everything else is optional
			tpe.execute(new Runnable() {
				@Override
				public void run() {
					try {
						tarArchiveWriter.writeFile("vmx", ld.configuration);
						tarArchiveWriter.writeFile("runscript", ld.legacyRunScript);
						tarArchiveWriter.writeFile("netshares", serializeNetShares(ld.netShares));
						if (ld.runScript != null) {
							int cnt = 0;
							for (RunScript rs : ld.runScript) {
								tarArchiveWriter.writeFile(String.format("adminrun/%04d-%d-%d.%s", cnt++, rs.visibility,
									rs.passCreds ? 1 : 0, rs.extension), rs.content);
							}
						}
					} catch (IOException e) {
						LOGGER.warn("Error writing to tar stream", e);
					} finally {
						Util.safeClose(tarArchiveWriter);
					}
				}
			});
		} catch (IOException e1) {
			LOGGER.warn("Could not create tar output stream", e1);
			return internalServerError();
		} catch (RejectedExecutionException e2) {
			LOGGER.warn("Server overloaded; rejecting VM Metadata request", e2);
			return internalServerError();
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/gzip", sink);
	}

	private Response serveLectureNetRules(String lectureId) {
		List<NetRule> list = new ArrayList<>();
		boolean defaultAllowed;
		try {
			defaultAllowed = DbLecture.getFirewallRules(lectureId, list);
		} catch (SQLException e) {
			return internalServerError();
		} catch (TNotFoundException e) {
			return notFound();
		}
		StringBuilder sb = new StringBuilder();
		for (NetRule rule : list) {
			sb.append(rule.direction.name());
			sb.append(' ');
			sb.append(rule.host);
			sb.append(' ');
			sb.append(rule.port);
			sb.append(' ');
			sb.append(defaultAllowed ? "REJECT" : "ACCEPT");
			sb.append('\n');
		}
		if (defaultAllowed) {
			sb.append("IN * 0 ACCEPT\n");
			sb.append("OUT * 0 ACCEPT\n");
		} else {
			sb.append("IN * 0 REJECT\n");
			sb.append("OUT * 0 REJECT\n");
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", sb.toString());
	}

	private String serializeNetShares(List<NetShare> list) {
		// openslx.exe expects shares in the following format
		// <path> <letter> <shortcut> <username> <password>
		// letter is either a drive letter for Windows VMs,
		// or a mount point for Linux VMs.
		StringBuilder sb = new StringBuilder();
		if (!list.isEmpty()) {
			for (NetShare share : list) {
				sb.append(share.path);
				sb.append('\t');
				sb.append(share.mountpoint);
				sb.append('\t');
				sb.append(share.displayname);
				if (share.auth == NetShareAuth.LOGIN_USER) {
					// TODO how to mark that it should use the logged in user's credentials
				}
				if (share.auth == NetShareAuth.OTHER_USER && share.isSetUsername()) {
					sb.append('\t');
					sb.append(share.username);
					if (share.isSetPassword()) {
						sb.append('\t');
						sb.append(share.password); // TODO fixme
					}
				}
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	/**
	 * Return full list of lectures matching given location(s).
	 * 
	 * @return
	 * @throws Exception
	 */
	private Response serveVmChooserList(Map<String, String> params) throws Exception {
		String locations = params.get("locations");
		boolean exams = params.containsKey("exams");

		VmChooserListXml listXml = DbLecture.getUsableListXml(exams, locations);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		serializer.write(listXml, baos);
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/xml; charset=utf-8",
				new ByteArrayInputStream(baos.toByteArray()));
	}

	/**
	 * Helper for returning "Internal Server Error" Status
	 * 
	 * @param body Message
	 */
	public static Response internalServerError(String body) {
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", body);
	}

	public static Response internalServerError() {
		return internalServerError("Internal Server Error");
	}

	/**
	 * Helper for returning "404 Not Found" Status
	 */
	public static Response notFound() {
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Nicht gefunden!");
	}

	/**
	 * Helper for returning "Bad Request" Status
	 */
	public static Response badRequest(String message) {
		if (message == null) {
			message = "Schlechte Anfrage!";
		}
		return new NanoHTTPD.Response(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", message);
	}

	/**
	 * create a json response with information about existing container images in
	 * bwlehrpool
	 */
	private Response serverContainerImages() {
		try {
			return new Response(Response.Status.OK, "application/json; charset=utf-8",
					Json.serialize(DbImage.getContainerImageCluster()));
		} catch (SQLException e) {
			LOGGER.error("error -- could not server container images", e);
			return internalServerError();
		}
	}

	private Response serveContainerImageMetaData(String imageBaseId) {
		try {
			return new Response(Response.Status.OK, "application/json; charset=utf-8",
					DbImage.getContainerImageMetadata(imageBaseId));
		} catch (SQLException e) {
			LOGGER.error("error -- could not server container image", e);
			return internalServerError();
		}
	}
}
