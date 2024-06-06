/*********************************************************************
* Copyright (c) 12.04.2024 Thomas Zierer
*
* This program and the accompanying materials are made
* available under the terms of the Eclipse Public License 2.0
* which is available at https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
**********************************************************************/
package de.tgmz.aqua.connection.zowe.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.NullInputStream;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;

import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.zos.comm.IZOSConnection.DataSetArguments;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.IZOSConstants.FileType;
import com.ibm.cics.zos.comm.IZOSConstants.JobStatus;
import com.ibm.cics.zos.comm.ZOSFileNotFoundException;
import com.ibm.cics.zos.comm.ZOSUnsupportedOperationException;

import de.tgmz.aqua.connection.zowe.connection.ZoweConnection;

public class ZoweConnectionMockTest {
	private static final String LOG_LEVEL_KEY = "org.slf4j.simpleLogger.defaultLogLevel";
	private static final String DS_NAME = "HLQ.FOO";
	private static final String MEMBER_NAME = "BAR";
	private static final String JOB_NAME = "FOOBAR";
	private static final String JOB_CARD = "//FOO JOB";
	private static final String USER = "foo";
	private static final String PASS = "bar";
	private static final String HFS_PATH = String.format("/%s/%s/", USER, PASS); // doesn't matter
	private static final String HTTP_DELETE = "DELETE";
	private static final String HTTP_GET = "GET";
	private static final String HTTP_POST = "POST";
	private static final String HTTP_PUT = "PUT";

	private enum ZosmfPaths {
		DATASETS("/zosmf/restfiles/ds"), FILES("/zosmf/restfiles/fs"), JOBS("/zosmf/restjobs/jobs");

		private String path;

		private ZosmfPaths(String path) {
			this.path = path;
		}

		public String getPath() {
			return path;
		}
	}

	private static String logLevel;
	private static ClientAndServer server;
	private static ZoweConnection connection;

	@BeforeClass
	public static void setupOnce() throws ConnectionException {
		logLevel = System.getProperty(LOG_LEVEL_KEY, "INFO");
		System.setProperty(LOG_LEVEL_KEY, "INFO"); // Set to "DEBUG" to force noisy logging

		HttpsURLConnection.setDefaultSSLSocketFactory(new KeyStoreFactory(Configuration.configuration(), new MockServerLogger()).sslContext().getSocketFactory());

		server = ClientAndServer.startClientAndServer(PortFactory.findFreePort());

		// Mock successful login
		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath("/zosmf/info")).respond(HttpResponse.response().withStatusCode(200));

		connection = new ZoweConnection();
		connection.connect(server.remoteAddress().getHostName(), server.getPort(), USER, PASS);
	}

	@AfterClass
	public static void teardownOnce() {
		System.setProperty(LOG_LEVEL_KEY, logLevel);
		connection.disconnect();
	}

	@Before
	public void setup() {
		server.reset();
	}

	@Test
	public void testCommon() {
		assertTrue(connection.canPerform(IZOSConstants.ACTION_SUPPORT_DATA_SETS, null));
		assertNotNull(connection.getConfiguration());
		assertEquals(server.remoteAddress().getHostName(), connection.getHost());
		assertEquals(server.getPort().intValue(), connection.getPort());
		assertTrue(connection.isConnected());
		assertTrue(connection.isSecure());
		assertEquals(ZoweConnection.CATEGORY_ID, connection.getName());
		assertEquals(USER, connection.getUserID());

		assertThrows(ZOSUnsupportedOperationException.class, () -> connection.perform(IZOSConstants.ACTION_SUPPORT_DATA_SETS, null));
	}

	@Test
	public void testDsnNotFound() throws IOException {
		String body = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/ds1.json"), StandardCharsets.UTF_8);

		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.DATASETS, ".*"))).respond(HttpResponse.response(body));
		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.FILES, ".*"))).respond(HttpResponse.response(body));

		assertThrows(ZOSFileNotFoundException.class, () -> connection.getDataSet(DS_NAME));
		assertThrows(ZOSFileNotFoundException.class, () -> connection.getDataSetMember(DS_NAME, MEMBER_NAME));
	}

	@Test
	public void testUss() throws IOException, ConnectionException {
		// Test with empty server
		assertThrows(ConnectionException.class, () -> connection.changePermissions(HFS_PATH, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.createFolderHFS(HFS_PATH));
		assertThrows(ConnectionException.class, () -> connection.deletePathHFS(HFS_PATH));
		assertFalse(connection.existsHFS(HFS_PATH));
		assertFalse(connection.existsHFSFile(HFS_PATH, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.getFileHFS(HFS_PATH, FileType.ASCII));
		assertThrows(ConnectionException.class, () -> connection.getHFSChildren(HFS_PATH, true));
		assertThrows(ConnectionException.class, () -> connection.saveFileHFS(HFS_PATH, new NullInputStream(), FileType.ASCII));
		assertThrows(ConnectionException.class, () -> connection.saveFileHFS(HFS_PATH, new NullInputStream(), StandardCharsets.UTF_8.name()));

		// Mock successful responses
		server.when(HttpRequest.request().withMethod(HTTP_PUT).withPath(getUri(ZosmfPaths.FILES, ".*"))).respond(HttpResponse.response().withStatusCode(201));
		server.when(HttpRequest.request().withMethod(HTTP_POST).withPath(getUri(ZosmfPaths.FILES, ".*"))).respond(HttpResponse.response().withStatusCode(201));
		server.when(HttpRequest.request().withMethod(HTTP_DELETE).withPath(getUri(ZosmfPaths.FILES, ".*"))).respond(HttpResponse.response().withStatusCode(204));

		String body = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/ds0.json"), StandardCharsets.UTF_8);

		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.FILES, ".*"))).respond(HttpResponse.response(body));

		// Test with mock server
		connection.changePermissions(HFS_PATH, MEMBER_NAME);
		connection.createFolderHFS(HFS_PATH);
		connection.deletePathHFS(HFS_PATH);
		assertTrue(connection.existsHFS(HFS_PATH));
		assertTrue(connection.existsHFSFile(HFS_PATH, MEMBER_NAME));
		assertNotNull(connection.getFileHFS(HFS_PATH, FileType.ASCII));
		assertEquals(3, connection.getHFSChildren(HFS_PATH, true).size());
		connection.saveFileHFS(HFS_PATH, new NullInputStream(), FileType.ASCII);
		connection.saveFileHFS(HFS_PATH, new NullInputStream(), StandardCharsets.UTF_8.name());
	}

	@Test
	public void testJobs() throws IOException, ConnectionException {
		// Test with empty server
		assertThrows(ConnectionException.class, () -> connection.cancelJob(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.deleteJob(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.getJob(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.getJobs("*", JobStatus.ALL, JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.getJobSpool(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.getJobSteps(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.getJobStepSpool(JOB_NAME));
		assertThrows(ConnectionException.class, () -> connection.submitDataSetMember(JOB_NAME, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.submitJob(IOUtils.toInputStream(JOB_CARD, Charset.defaultCharset())));

		// Mock successful responses
		String s0 = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/jcl0.json"), StandardCharsets.UTF_8);
		String s1 = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/jcl1.json"), StandardCharsets.UTF_8);
		String s2 = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/jcl2.json"), StandardCharsets.UTF_8);
		s2 = s2.replace("localhost:80", String.format("%s:%s", server.remoteAddress().getHostName(), server.getPort()));

		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.JOBS, ".*files*"))).respond(HttpResponse.response(s2));
		server.when(HttpRequest.request().withMethod(HTTP_PUT).withPath(getUri(ZosmfPaths.JOBS, ".*"))).respond(HttpResponse.response(s1));
		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.JOBS, ".*"))).respond(HttpResponse.response(s0));

		server.when(HttpRequest.request().withMethod(HTTP_DELETE).withPath(getUri(ZosmfPaths.JOBS, "/.*"))).respond(HttpResponse.response().withStatusCode(204));

		// Test with mock server
		connection.cancelJob(JOB_NAME);
		connection.deleteJob(JOB_NAME);
		assertNotNull(connection.getJob(JOB_NAME));
		assertEquals(0, connection.getJobs("*", JobStatus.INPUT, JOB_NAME).size());
		assertEquals(1, connection.getJobs("*", JobStatus.OUTPUT, JOB_NAME).size());
		assertEquals(1, connection.getJobs("*", JobStatus.ALL, JOB_NAME).size());
		assertNotNull(connection.getJobSpool(JOB_NAME));
		assertEquals(5, connection.getJobSteps(JOB_NAME).size());
		assertNotNull(connection.getJobStepSpool(String.format("%s.%s", JOB_NAME, "101")));
		assertNotNull(connection.submitDataSetMember(JOB_NAME, MEMBER_NAME));
		assertNotNull(connection.submitJob(IOUtils.toInputStream(JOB_CARD, Charset.defaultCharset())));

		mockCompletionCode("CC 0000");
		mockCompletionCode("CC 0012");
		mockCompletionCode("JCL ERROR");
		mockCompletionCode("ABEND U4000");
		mockCompletionCode("SYSFAIL");
		mockCompletionCode("FOO");
	}

	@Test
	public void testDsn() throws IOException, ConnectionException {
		// Test with empty server
		assertThrows(ConnectionException.class, () -> connection.deleteDataSet(DS_NAME, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.deleteDataSet(null, DS_NAME));
		assertThrows(ConnectionException.class, () -> connection.getDataSet(DS_NAME));
		assertThrows(ConnectionException.class, () -> connection.getDataSetMember(DS_NAME, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.getDataSetMembers(DS_NAME));

		DataSetArguments dsa = new DataSetArguments();
		dsa.datasetType = "PDSE";
		dsa.spaceUnits = "CYLINDERS";

		assertThrows(ConnectionException.class, () -> connection.createDataSet(DS_NAME, dsa));
		assertThrows(ConnectionException.class, () -> connection.createDataSet(DS_NAME, MEMBER_NAME, new NullInputStream()));
		assertThrows(ConnectionException.class, () -> connection.createDataSetMember(DS_NAME, MEMBER_NAME));
		assertThrows(ZOSUnsupportedOperationException.class, () -> connection.recallDataSetMember(DS_NAME, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.retrieveSequentialDataSet(DS_NAME));
		assertThrows(ConnectionException.class, () -> connection.retrieveDataSetMember(DS_NAME, MEMBER_NAME));
		assertThrows(ConnectionException.class, () -> connection.saveDataSetMember(DS_NAME, MEMBER_NAME, new NullInputStream()));

		// Mock successful responses
		server.when(HttpRequest.request().withMethod(HTTP_DELETE).withPath(getUri(ZosmfPaths.DATASETS, "/.*"))).respond(HttpResponse.response().withStatusCode(204));
		server.when(HttpRequest.request().withMethod(HTTP_POST).withPath(getUri(ZosmfPaths.DATASETS, "/.*"))).respond(HttpResponse.response().withStatusCode(201));
		server.when(HttpRequest.request().withMethod(HTTP_PUT).withPath(getUri(ZosmfPaths.DATASETS, "/.*"))).respond(HttpResponse.response().withStatusCode(201));

		String body = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/ds0.json"), StandardCharsets.UTF_8);

		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.DATASETS, ".*"))).respond(HttpResponse.response(body));

		// Test with mock server
		connection.deleteDataSet(DS_NAME, MEMBER_NAME);
		connection.deleteDataSet(null, DS_NAME);
		assertNotNull(connection.getDataSet(DS_NAME));
		assertNotNull(connection.getDataSetMember(DS_NAME, MEMBER_NAME));
		assertNotNull(connection.getDataSetMembers(DS_NAME));

		for (String unit : Arrays.asList("CYLINDERS", "TRACKS", "BLOCKS", "UNSPECIFIED")) {
			dsa.spaceUnits = unit;
			connection.createDataSet(DS_NAME, dsa);
		}

		connection.createDataSet(DS_NAME, MEMBER_NAME, new NullInputStream());
		assertNotNull(connection.createDataSetMember(DS_NAME, MEMBER_NAME));
		assertThrows(ZOSUnsupportedOperationException.class, () -> connection.recallDataSetMember(DS_NAME, MEMBER_NAME));
		assertNotNull(connection.retrieveSequentialDataSet(DS_NAME));
		assertNotNull(connection.retrieveDataSetMember(DS_NAME, MEMBER_NAME));
		connection.saveDataSetMember(DS_NAME, MEMBER_NAME, new NullInputStream());

	}

	private static String getUri(ZosmfPaths path, String pattern) {
		return String.format("%s%s", path.getPath(), pattern);
	}
	
	private void mockCompletionCode(String cc) throws IOException, ConnectionException {
		String s = IOUtils.toString(ZoweConnectionMockTest.class.getClassLoader().getResource("testresources/jcl0.json"), StandardCharsets.UTF_8);		
		
		server.reset();
		
		s = s.replace("CC 0004", cc);
		server.when(HttpRequest.request().withMethod(HTTP_GET).withPath(getUri(ZosmfPaths.JOBS, ".*"))).respond(HttpResponse.response(s));
		assertNotNull(connection.getJob(JOB_NAME));
	}
}
