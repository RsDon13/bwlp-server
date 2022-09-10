package org.openslx.bwlp.sat.fileserv;

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.thrift.iface.TInvalidTokenException;
import org.openslx.bwlp.thrift.iface.TransferInformation;
import org.openslx.filetransfer.Uploader;
import org.openslx.filetransfer.util.OutgoingTransferBase;
import org.openslx.thrifthelper.ThriftManager;

public class OutgoingDataTransfer extends OutgoingTransferBase {

	private static final Logger LOGGER = LogManager.getLogger(OutgoingDataTransfer.class);

	private final TransferInformation masterTransferInfo;
	
	private final String versionId;

	/**
	 * For downloads by clients.
	 * 
	 * @param uuid UUID of this transfer
	 * @param file file to send to client
	 */
	public OutgoingDataTransfer(String uuid, File file, int plainPort, int sslPort, String versionId) {
		super(uuid, file, plainPort, sslPort);
		this.masterTransferInfo = null;
		this.versionId = versionId;
	}

	/**
	 * For uploads to the master server.
	 * 
	 * @param transferInfo TI received by master server when it granted the
	 *            upload
	 * @param absFile file to send to master server
	 */
	public OutgoingDataTransfer(TransferInformation transferInfo, File absFile, String versionId) {
		super(transferInfo.token, absFile, 0, 0);
		this.masterTransferInfo = transferInfo;
		this.versionId = versionId;
	}

	/**
	 * Called periodically if this is a transfer from the master server, so we
	 * can make sure the transfer is running.
	 */
	public synchronized void heartBeat(ExecutorService pool) {
		if (masterTransferInfo == null)
			return;
		if (connectFailCount() > 50)
			return;
		if (connectFailCount() > 5) {
			try {
				ThriftManager.getMasterClient().queryUploadStatus(masterTransferInfo.token);
			} catch (TInvalidTokenException e) {
				LOGGER.info("Master server forgot about upload " + masterTransferInfo.token + ", aborting...");
				connectFails.set(100);
				return;
			} catch (TException e) {
				LOGGER.warn("Cannot query master server for upload status of " + masterTransferInfo.token, e);
			}
		}
		if (getActiveConnectionCount() >= 1)
			return;
		Uploader uploader = null;
		Exception connectException = null;
		if (masterTransferInfo.plainPort != 0) {
			// Try plain
			try {
				uploader = new Uploader(Configuration.getMasterServerAddress(), masterTransferInfo.plainPort,
						10000, null, masterTransferInfo.token);
			} catch (IOException e) {
				uploader = null;
				connectException = e;
			}
		}
		if (uploader == null && masterTransferInfo.sslPort != 0 && Configuration.getMasterServerSsl()) {
			// Try SSL
			try {
				uploader = new Uploader(Configuration.getMasterServerAddress(), masterTransferInfo.sslPort,
						10000, Configuration.getMasterServerSslContext(), masterTransferInfo.token);
			} catch (KeyManagementException | NoSuchAlgorithmException | IOException e) {
				connectException = e;
			}
		}
		if (uploader == null) {
			LOGGER.debug("Cannot connect to master server for uploading", connectException);
		} else {
			runConnectionInternal(uploader, pool);
		}
	}

	@Override
	public String getRelativePath() {
		throw new RuntimeException("Not implemented");
	}

	public Object getVersionId() {
		return versionId;
	}

}
