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
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.IZOSConstants.JobStatus;
import com.ibm.cics.zos.comm.ZOSConnectionResponse;
import com.ibm.cics.zos.model.IJob;

import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.rest.Response;
import zowe.client.sdk.rest.ZosmfRequest;
import zowe.client.sdk.rest.ZosmfRequestFactory;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.rest.type.ZosmfRequestType;
import zowe.client.sdk.zosjobs.input.JobGetInputData;
import zowe.client.sdk.zosjobs.methods.JobCancel;
import zowe.client.sdk.zosjobs.methods.JobDelete;
import zowe.client.sdk.zosjobs.methods.JobGet;
import zowe.client.sdk.zosjobs.methods.JobSubmit;
import zowe.client.sdk.zosjobs.model.Job;
import zowe.client.sdk.zosjobs.model.JobFile;

public class ZoweJobConnection {
	private static final Logger LOG = LoggerFactory.getLogger(ZoweJobConnection.class);

	private ZosConnection connection;

	private Response response;

	private JobGet jobGet;
	private JobSubmit jobSubmit;
	private JobDelete jobDelete;
	private JobCancel jobCancel;

	public ZoweJobConnection(ZosConnection connection) {
		this.connection = connection;

		jobGet = new JobGet(connection);
		jobSubmit = new JobSubmit(connection);
		jobDelete = new JobDelete(connection);
		jobCancel = new JobCancel(connection);

	}

	public ZOSConnectionResponse getJob(String jobID) throws ConnectionException {
		LOG.debug("getJob {}", jobID);

		Job byId;

		try {
			byId = jobGet.getById(jobID);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		return convertJob(byId);
	}

	public ByteArrayOutputStream getJobStepSpool(String jobID) throws ConnectionException {
		LOG.debug("getJobStepSpool {}", jobID);

		String[] split = jobID.split("\\.");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {
			Job jobs = jobGet.getById(split[0]);
			List<JobFile> files = jobGet.getSpoolFilesByJob(jobs);

			for (JobFile file : files) {
				if (Long.parseLong(split[1]) == file.getId()) {
					response = download(file.getRecordsUrl());

					baos.write(((String) response.getResponsePhrase().orElse("")).getBytes());

					break;
				}
			}
		} catch (ZosmfRequestException |IOException e) {
			throw new ConnectionException(e);
		}

		return baos;
	}

	public List<ZOSConnectionResponse> getJobSteps(String jobID) throws ConnectionException {
		LOG.debug("getJobSteps} {}", jobID);
		
		List<JobFile> spoolFilesByJob;

		try {
			Job byId = jobGet.getById(jobID);
			spoolFilesByJob = jobGet.getSpoolFilesByJob(byId);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		List<ZOSConnectionResponse> result = new ArrayList<>(spoolFilesByJob.size());

		for (JobFile jf : spoolFilesByJob) {
			ZOSConnectionResponse cr = new ZOSConnectionResponse();

			String id = jf.getJobId();

			cr.addAttribute(IZOSConstants.JOB_STEPNAME, id + "." + jf.getDdName());
			cr.addAttribute(IZOSConstants.JOB_ID, id);
			cr.addAttribute(IZOSConstants.JOB_DDNAME, jf.getDdName());
			cr.addAttribute(IZOSConstants.JOB_DSNAME, id + "." + jf.getId());
			cr.addAttribute(IZOSConstants.JOB_SPOOL_FILES_AVAILABLE, true);

			result.add(cr);
		}

		return result;
	}

	public List<ZOSConnectionResponse> getJobs(String jobName, IZOSConstants.JobStatus aJobStatus, String owner) throws ConnectionException {
		LOG.debug("getJobs {} {} {}", jobName, aJobStatus, owner);

		List<ZOSConnectionResponse> result = new LinkedList<>();
		List<Job> jobs;

		JobGetInputData params = new JobGetInputData.Builder(owner).prefix(jobName).build();

		try {
			jobs = jobGet.getCommon(params);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		for (Job job : jobs) {
			if (aJobStatus == JobStatus.ALL
					|| JobStatus.valueOf(job.getStatus()) == aJobStatus) {
				result.add(convertJob(job));
			}
		}

		return result;
	}

	public ByteArrayOutputStream submitDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("submitDataSetMember {} {}", dataSetName, memberName);

		try {
			Job job = jobSubmit.submit(String.format("%s(%s)", dataSetName, memberName));

			LOG.debug("jobSubmit {}", job);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		return new ByteArrayOutputStream(0);
	}

	public ByteArrayOutputStream getJobSpool(String jobID) throws ConnectionException {
		LOG.debug("getJobSpool {}", jobID);

		JobGetInputData params = new JobGetInputData.Builder("*").jobId(jobID).build();

		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			List<Job> jobs = jobGet.getCommon(params);
			List<JobFile> files = jobGet.getSpoolFilesByJob(jobs.get(0));

			StringBuilder sb = new StringBuilder();

			for (JobFile jobFile : files) {
				response = download(jobFile.getRecordsUrl());

				sb.append((String) response.getResponsePhrase().orElse(""));
			}

			baos.write(sb.toString().getBytes());

			return baos;
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}
	}

	public ZOSConnectionResponse submitJob(InputStream stream) throws ConnectionException {
		LOG.debug("submitJob {}", stream);

		Job job;

		try (Reader r = new InputStreamReader(stream)) {
			job = jobSubmit.submitByJcl(IOUtils.toString(r), null, null);
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}

		ZOSConnectionResponse cr = new ZOSConnectionResponse();
		cr.addAttribute(IZOSConstants.JOB_NAME, job.getJobName());
		cr.addAttribute(IZOSConstants.JOB_ID, job.getJobId());
		cr.addAttribute(IZOSConstants.JOB_USER, job.getOwner());

		return cr;
	}

	public void deleteJob(String jobId) throws ConnectionException {
		LOG.debug("deleteJob {}", jobId);

		try {
			Job byId = jobGet.getById(jobId);
			response = jobDelete.deleteByJob(byId, "2.0");

			LOG.debug("jobDelete {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	public void cancelJob(String jobId) throws ConnectionException {
		LOG.debug("cancelJob {}", jobId);

		try {
			Job byId = jobGet.getById(jobId);
			response = jobCancel.cancelByJob(byId, null);

			LOG.debug("jobCancel {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	private ZOSConnectionResponse convertJob(Job job) {
		ZOSConnectionResponse cr = new ZOSConnectionResponse();

		cr.addAttribute(IZOSConstants.NAME, job.getJobName());
		cr.addAttribute(IZOSConstants.JOB_ID, job.getJobId());
		cr.addAttribute(IZOSConstants.JOB_USER, job.getOwner());
		cr.addAttribute(IZOSConstants.JOB_STATUS, IZOSConstants.JobStatus.valueOf(job.getStatus()));
		cr.addAttribute(IZOSConstants.JOB_CLASS, job.getClasss());
		cr.addAttribute(IZOSConstants.JOB_SPOOL_FILES_AVAILABLE, true);
		cr.addAttribute(IZOSConstants.JOB_HAS_SPOOL_FILES, true);

		String retCode = job.getRetCode();
			
		IJob.JobCompletion jc;
			
		String[] split = retCode.split("[\\s]", 2);
			
		switch (split[0]) {
		case "JCL":
			jc = IJob.JobCompletion.JCLERROR;
			break;
		case "CC":
			if ("0000".equals(split[1])) {
				jc = IJob.JobCompletion.NORMAL;
			} else {
				jc = IJob.JobCompletion.BADRETURNCODE;
			}
			break;
		case "ABEND":
			jc = IJob.JobCompletion.ABEND;
			break;
		default:
			try {
				jc = IJob.JobCompletion.valueOf(split[0]);
			} catch (IllegalArgumentException e) {
				jc = IJob.JobCompletion.NA;
			}
		}
			
		cr.addAttribute(IZOSConstants.JOB_ERROR_CODE, split.length == 2 ? split[1] : "");
		cr.addAttribute(IZOSConstants.JOB_COMPLETION, jc);

		return cr;
	}

	private Response download(String url) throws ZosmfRequestException {
		ZosmfRequest request = ZosmfRequestFactory.buildRequest(connection, ZosmfRequestType.GET_TEXT);

		request.setUrl(url);

		return request.executeRequest();
	}
}
