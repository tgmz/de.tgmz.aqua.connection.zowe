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
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.IZOSConstants.JobStatus;
import com.ibm.cics.zos.comm.ZOSConnectionResponse;
import com.ibm.cics.zos.model.IJob;
import com.ibm.cics.zos.model.IJob.JobCompletion;

import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.rest.Response;
import zowe.client.sdk.rest.ZosmfRequest;
import zowe.client.sdk.rest.ZosmfRequestFactory;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.rest.type.ZosmfRequestType;
import zowe.client.sdk.zosjobs.input.GetJobParams;
import zowe.client.sdk.zosjobs.input.JobFile;
import zowe.client.sdk.zosjobs.methods.JobCancel;
import zowe.client.sdk.zosjobs.methods.JobDelete;
import zowe.client.sdk.zosjobs.methods.JobGet;
import zowe.client.sdk.zosjobs.methods.JobSubmit;
import zowe.client.sdk.zosjobs.response.Job;

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
				if (Long.parseLong(split[1]) == file.getId().orElse(0L)) {
					response = download(file.getRecordsUrl().orElseThrow(() -> new ConnectionException("No download URL available")));

					baos.writeBytes(((String) response.getResponsePhrase().orElse("")).getBytes());

					break;
				}
			}
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		return baos;
	}

	public List<ZOSConnectionResponse> getJobSteps(String jobID) throws ConnectionException {
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

			String id = jf.getJobId().orElse(jobID);

			cr.addAttribute(IZOSConstants.JOB_STEPNAME, id + "." + jf.getDdName().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.JOB_ID, id);
			cr.addAttribute(IZOSConstants.JOB_DDNAME, jf.getDdName().orElse(jobID));
			cr.addAttribute(IZOSConstants.JOB_DSNAME, id + "." + jf.getId().orElse(0L));
			cr.addAttribute(IZOSConstants.JOB_SPOOL_FILES_AVAILABLE, true);

			result.add(cr);
		}

		return result;
	}

	public List<ZOSConnectionResponse> getJobs(String jobName, IZOSConstants.JobStatus aJobStatus, String owner) throws ConnectionException {
		LOG.debug("getJobs {} {} {}", jobName, aJobStatus, owner);

		List<ZOSConnectionResponse> result = new LinkedList<>();
		List<Job> jobs;

		GetJobParams params = new GetJobParams.Builder(owner).prefix(jobName).build();

		try {
			jobs = jobGet.getCommon(params);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		for (Job job : jobs) {
			if (aJobStatus == JobStatus.ALL
					|| JobStatus.valueOf(job.getStatus().orElse(ZoweConnection.UNKNOWN)) == aJobStatus) {
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

		GetJobParams params = new GetJobParams.Builder("*").jobId(jobID).build();

		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			List<Job> jobs = jobGet.getCommon(params);
			List<JobFile> files = jobGet.getSpoolFilesByJob(jobs.get(0));

			StringBuilder sb = new StringBuilder();

			for (JobFile jobFile : files) {
				Optional<String> oString = jobFile.getRecordsUrl();

				if (oString.isPresent()) {
					response = download(oString.get());

					sb.append((String) response.getResponsePhrase().orElse(""));
				}
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
		cr.addAttribute(IZOSConstants.JOB_NAME, job.getJobName().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_ID, job.getJobId().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_USER, job.getOwner().orElse(connection.getUser()));

		return cr;
	}

	public void deleteJob(String jobId) throws ConnectionException {
		LOG.debug("deleteJob {}", jobId);

		try {
			Job byId = jobGet.getById(jobId);
			response = jobDelete.deleteByJob(createJobBuilder(jobId, byId), "2.0");

			LOG.debug("jobDelete {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	public void cancelJob(String jobId) throws ConnectionException {
		LOG.debug("cancelJob {}", jobId);

		try {
			Job byId = jobGet.getById(jobId);
			response = jobCancel.cancelByJob(createJobBuilder(jobId, byId), null);

			LOG.debug("jobCancel {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	private ZOSConnectionResponse convertJob(Job job) {
		ZOSConnectionResponse cr = new ZOSConnectionResponse();

		cr.addAttribute(IZOSConstants.NAME, job.getJobName().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_ID, job.getJobId().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_USER, job.getOwner().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_STATUS, IZOSConstants.JobStatus.valueOf(job.getStatus().orElse(ZoweConnection.UNKNOWN)));
		cr.addAttribute(IZOSConstants.JOB_CLASS, job.getClasss().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_COMPLETION, job.getRetCode().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_SPOOL_FILES_AVAILABLE, true);
		cr.addAttribute(IZOSConstants.JOB_ERROR_CODE, job.getRetCode().orElse(ZoweConnection.UNKNOWN));
		cr.addAttribute(IZOSConstants.JOB_HAS_SPOOL_FILES, true);

		Optional<String> oStatus = job.getStatus();

		if (oStatus.isPresent()) {
			String status = oStatus.get();
			JobCompletion jc;
			try {
				jc = IJob.JobCompletion.valueOf(oStatus.get());
			} catch (IllegalArgumentException e) {
				jc = "OUTPUT".equals(status) ? JobCompletion.NORMAL : JobCompletion.NA;
			}

			cr.addAttribute(IZOSConstants.JOB_COMPLETION, jc);
		}

		return cr;
	}

	private Response download(String url) throws ZosmfRequestException {
		ZosmfRequest request = ZosmfRequestFactory.buildRequest(connection, ZosmfRequestType.GET_TEXT);

		request.setUrl(url);

		return request.executeRequest();
	}
	
	private Job createJobBuilder(String jobId, Job job) throws ConnectionException {
		return new Job.Builder()
				.jobId(jobId)
				.jobName(job.getJobName().orElseThrow(() -> new ConnectionException(String.format("Job %s not found", jobId))))
				.build();
	}
}
