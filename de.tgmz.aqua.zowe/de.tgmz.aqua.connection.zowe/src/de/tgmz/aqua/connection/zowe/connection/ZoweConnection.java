/*********************************************************************
* Copyright (c) 12.04.2024 Thomas Zierer
*
* This program and the accompanying materials are made
* available under the terms of the Eclipse Public License 2.0
* which is available at https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
**********************************************************************/
package de.tgmz.aqua.connection.zowe.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.core.comm.CredentialsConfiguration;
import com.ibm.cics.core.comm.ExplorerSecurityHelper;
import com.ibm.cics.core.connections.ConnectionsPlugin;
import com.ibm.cics.zos.comm.AbstractZOSConnection;
import com.ibm.cics.zos.comm.IZOSConnection;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.ZOSConnectionResponse;

import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.core.ZosConnectionFactory;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.zosmfinfo.methods.ZosmfStatus;
import zowe.client.sdk.zosmfinfo.response.ZosmfInfoResponse;

public class ZoweConnection extends AbstractZOSConnection implements IZOSConnection {
	private static final Logger LOG = LoggerFactory.getLogger(ZoweConnection.class);

	public static final String CATEGORY_ID = "com.ibm.cics.zos.comm.connection";

	public static final String UNKNOWN = "UNKNOWN";

	private boolean connected;

	private ZosConnection connection;

	private ZoweUssConnection ussConnection;
	private ZoweJobConnection jobConnection;
	private ZoweDsnConnection dsnConnection;

	private SSLContext sslContext;

	@Override
	public void connect() throws ConnectionException {
		CredentialsConfiguration cc = ConnectionsPlugin.getDefault().getCredentialsManager().findCredentialsConfigurationByID(super.getConfiguration().getCredentialsID());

		this.connect(getConfiguration().getHost(), getConfiguration().getPort(), cc.getUserID(), cc.getPassword());
	}

	public void connect(String host, int port, String user, String pass) throws ConnectionException {
		connection = ZosConnectionFactory.createBasicConnection(host, String.valueOf(port), user, pass);

		initSSLConfiguration();

		ussConnection = new ZoweUssConnection(connection);
		jobConnection = new ZoweJobConnection(connection);
		dsnConnection = new ZoweDsnConnection(connection);

		ZosmfStatus zosmfStatus = new ZosmfStatus(connection);

		try {
			ZosmfInfoResponse zosmfInfoResponse = zosmfStatus.get();

			String realHost = zosmfInfoResponse.getZosmfHostName();
			String osVersion = zosmfInfoResponse.getZosVersion();

			connected = true;

			LOG.info("Connected to {} running on z/OS version {}", realHost, osVersion);
		} catch (ZosmfRequestException e) {
			connected = false;

			throw new ConnectionException(e);
		}
	}

	@Override
	public void disconnect() {
		connection = null;
		connected = false;
	}

	@Override
	public String getHost() {
		return connection.getHost();
	}

	@Override
	public String getName() {
		return CATEGORY_ID;
	}

	@Override
	public int getPort() {
		return Integer.parseInt(connection.getZosmfPort());
	}

	@Override
	public String getUserID() {
		return connection.getUser();
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public boolean isSecure() {
		return sslContext != null;
	}

	@Override
	public ZOSConnectionResponse getJob(String jobID) throws ConnectionException {
		return jobConnection.getJob(jobID);
	}

	@Override
	public ByteArrayOutputStream getJobStepSpool(String jobID) throws ConnectionException {
		return jobConnection.getJobStepSpool(jobID);
	}

	@Override
	public List<ZOSConnectionResponse> getJobSteps(String jobID) throws ConnectionException {
		return jobConnection.getJobSteps(jobID);
	}

	@Override
	public List<ZOSConnectionResponse> getJobs(String jobName, JobStatus aJobStatus, String owner)
			throws ConnectionException {
		return jobConnection.getJobs(jobName, aJobStatus, owner);
	}

	@Override
	public List<ZOSConnectionResponse> getDataSetMembers(String dataSetName) throws ConnectionException {
		return dsnConnection.getDataSetMembers(dataSetName);
	}

	@Override
	public ByteArrayOutputStream retrieveDataSetMember(String dataSetName, String memberName)
			throws ConnectionException {
		return dsnConnection.retrieveDataSetMember(dataSetName, memberName);
	}

	@Override
	public void recallDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		dsnConnection.recallDataSetMember(dataSetName, memberName);
	}

	@Override
	public ByteArrayOutputStream retrieveSequentialDataSet(String dataSetName) throws ConnectionException {
		return dsnConnection.retrieveSequentialDataSet(dataSetName);
	}

	@Override
	public ByteArrayOutputStream submitDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		return jobConnection.submitDataSetMember(dataSetName, memberName);
	}

	@Override
	public void saveDataSetMember(String dataSetName, String memberName, InputStream dataSetContents)
			throws ConnectionException {
		dsnConnection.saveDataSetMember(dataSetName, memberName, dataSetContents);
	}

	@Override
	public void deleteDataSet(String dataSetName, String memberName) throws ConnectionException {
		dsnConnection.deleteDataSet(dataSetName, memberName);
	}

	@Override
	public void createDataSet(String dataSetName, DataSetArguments dataSetArguments) throws ConnectionException {
		dsnConnection.createDataSet(dataSetName, dataSetArguments);
	}

	@Override
	public ZOSConnectionResponse getDataSet(String dataSetName) throws ConnectionException {
		return dsnConnection.getDataSet(dataSetName);
	}

	@Override
	public ZOSConnectionResponse getDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		return dsnConnection.getDataSetMember(dataSetName, memberName);
	}

	@Override
	public ZOSConnectionResponse createDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		return dsnConnection.createDataSetMember(dataSetName, memberName);
	}

	@Override
	public void createDataSet(String dataSetName, String basedOnDataSetPath, InputStream contents)
			throws ConnectionException {
		dsnConnection.createDataSet(dataSetName, basedOnDataSetPath, contents);
	}

	@Override
	public List<ZOSConnectionResponse> getHFSChildren(String aPath, boolean includeHiddenFiles)
			throws ConnectionException {
		return ussConnection.getHFSChildren(aPath, includeHiddenFiles);
	}

	@Override
	public boolean existsHFS(String aPath) throws ConnectionException {
		return ussConnection.existsHFS(aPath);
	}

	@Override
	public boolean existsHFSFile(String aPath, String aName) throws ConnectionException {
		return ussConnection.existsHFSFile(aPath, aName);
	}

	@Override
	public void createFolderHFS(String aPath) throws ConnectionException {
		ussConnection.createFolderHFS(aPath);
	}

	@Override
	public void deletePathHFS(String aPath) throws ConnectionException {
		ussConnection.deletePathHFS(aPath);
	}

	@Override
	public void saveFileHFS(String aPath, InputStream fileContents, IZOSConstants.FileType aFileType)
			throws ConnectionException {
		ussConnection.saveFileHFS(aPath, fileContents, aFileType);
	}

	@Override
	public void saveFileHFS(String filePath, InputStream fileContents, String charset) throws ConnectionException {
		ussConnection.saveFileHFS(filePath, fileContents, charset);
	}

	@Override
	public ByteArrayOutputStream getFileHFS(String fileName, FileType p1) throws ConnectionException {
		return ussConnection.getFileHFS(fileName, p1);
	}

	@Override
	public ByteArrayOutputStream getJobSpool(String jobId) throws ConnectionException {
		return jobConnection.getJobSpool(jobId);
	}

	@Override
	public ZOSConnectionResponse submitJob(InputStream stream) throws ConnectionException {
		return jobConnection.submitJob(stream);
	}

	@Override
	public void deleteJob(String jobId) throws ConnectionException {
		jobConnection.deleteJob(jobId);
	}

	@Override
	public void cancelJob(String jobId) throws ConnectionException {
		jobConnection.cancelJob(jobId);
	}

	@Override
	public boolean canPerform(String actionID, String iD) {
		return true;
	}

	@Override
	public void perform(String request, String argument) throws ConnectionException {
		LOG.debug("perform {} {}", request, argument);
		super.perform(request, argument);
	}

	@Override
	public void changePermissions(String aHFSEntry, String octal) throws ConnectionException {
		ussConnection.changePermissions(aHFSEntry, octal);
	}

	private void initSSLConfiguration() {
		try {
			Object[] helper = ExplorerSecurityHelper.getSSLContext(getConfiguration().getName(), getConfiguration().getHost());
			sslContext = (SSLContext) helper[0];
		} catch (IOException e) {
			LOG.info("Cannot get SSL context", e);
		}
	}
}
