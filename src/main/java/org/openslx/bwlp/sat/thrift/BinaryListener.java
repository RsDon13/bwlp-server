package org.openslx.bwlp.sat.thrift;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFastFramedTransport;
import org.openslx.bwlp.sat.util.Identity;
import org.openslx.bwlp.thrift.iface.SatelliteServer;
import org.openslx.thrifthelper.TBinaryProtocolSafe;

public class BinaryListener implements Runnable {
	private static final Logger log = LogManager.getLogger(BinaryListener.class);

	private static final int MAX_MSG_LEN = 30 * 1000 * 1000;
	private static final int MINWORKERTHREADS = 2;
	private static final int MAXWORKERTHREADS = 96;

	private final SatelliteServer.Processor<ServerHandler> processor = new SatelliteServer.Processor<ServerHandler>(
			new ServerHandler());
	private final TProtocolFactory protFactory = new TBinaryProtocolSafe.Factory(true, true);

	private final TServer server;

	public BinaryListener(int port, boolean secure) throws TTransportException, NoSuchAlgorithmException,
			IOException {
		if (secure)
			server = initSecure(port);
		else
			server = initNormal(port);
	}

	@Override
	public void run() {
		log.info("Starting Listener");
		server.serve();
		log.fatal("Listener stopped unexpectedly");
		// TODO: Restart listener; if it fails, quit server so it will be restarted by the OS
	}

	private TServer initSecure(int port) throws NoSuchAlgorithmException, TTransportException, IOException {
		SSLContext context = Identity.getSSLContext();
		if (context == null)
			return null;
		SSLServerSocketFactory sslServerSocketFactory = context.getServerSocketFactory();
		ServerSocket listenSocket = sslServerSocketFactory.createServerSocket();
		listenSocket.setReuseAddress(true);
		listenSocket.bind(new InetSocketAddress(port));

		TServerTransport serverTransport;
		try {
			serverTransport = new TServerSocket(listenSocket);
		} catch (TTransportException e) {
			log.fatal("Could not listen on port " + port);
			throw e;
		}
		TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport);
		args.protocolFactory(protFactory);
		args.processor(processor);
		args.minWorkerThreads(MINWORKERTHREADS).maxWorkerThreads(MAXWORKERTHREADS);
		args.stopTimeoutVal(2).stopTimeoutUnit(TimeUnit.MINUTES);
		args.transportFactory(new TFastFramedTransport.Factory(MAX_MSG_LEN));
		return new TThreadPoolServer(args);
	}

	private TServer initNormal(int port) throws TTransportException {
		final TNonblockingServerTransport serverTransport;
		try {
			serverTransport = new TNonblockingServerSocket(port);
			log.info("Listening on port " + port + " (plain handler)");
		} catch (TTransportException e) {
			log.info("Could not listen on port " + port + " (plain handler)");
			throw e;
		}
		THsHaServer.Args args = new THsHaServer.Args(serverTransport);
		args.protocolFactory(protFactory);
		args.processor(processor);
		args.maxWorkerThreads(8);
		args.maxReadBufferBytes = MAX_MSG_LEN;
		return new THsHaServer(args);
	}

}
