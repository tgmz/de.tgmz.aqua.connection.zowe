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
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.zos.comm.IZOSConnection.DataSetArguments;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.ZOSConnectionResponse;
import com.ibm.cics.zos.comm.ZOSFileNotFoundException;
import com.ibm.cics.zos.comm.ZOSUnsupportedOperationException;

import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.rest.Response;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.zosfiles.dsn.input.CopyParams;
import zowe.client.sdk.zosfiles.dsn.input.CreateParams;
import zowe.client.sdk.zosfiles.dsn.input.DownloadParams;
import zowe.client.sdk.zosfiles.dsn.input.ListParams;
import zowe.client.sdk.zosfiles.dsn.methods.DsnCopy;
import zowe.client.sdk.zosfiles.dsn.methods.DsnCreate;
import zowe.client.sdk.zosfiles.dsn.methods.DsnDelete;
import zowe.client.sdk.zosfiles.dsn.methods.DsnGet;
import zowe.client.sdk.zosfiles.dsn.methods.DsnList;
import zowe.client.sdk.zosfiles.dsn.methods.DsnWrite;
import zowe.client.sdk.zosfiles.dsn.response.Dataset;
import zowe.client.sdk.zosfiles.dsn.response.Member;
import zowe.client.sdk.zosfiles.dsn.types.AttributeType;

public class ZoweDsnConnection {
	private static final Logger LOG = LoggerFactory.getLogger(ZoweDsnConnection.class);

	private Response response;

	private DsnGet dsnGet;
	private DsnWrite dsnWrite;
	private DsnDelete dsnDelete;
	private DsnList dsnList;
	private DsnCreate dsnCreate;
	private DsnCopy dsnCopy;

	public ZoweDsnConnection(ZosConnection connection) {
		dsnWrite = new DsnWrite(connection);
		dsnDelete = new DsnDelete(connection);
		dsnGet = new DsnGet(connection);
		dsnList = new DsnList(connection);
		dsnCreate = new DsnCreate(connection);
		dsnCopy = new DsnCopy(connection);
	}

	public List<ZOSConnectionResponse> getDataSetMembers(String dataSetName) throws ConnectionException {
		LOG.debug("getDataSetMembers {}", dataSetName);

		return dataSetName.endsWith("*") ? getDataSets(dataSetName) : getMembers(dataSetName);
	}

	public ByteArrayOutputStream retrieveDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("retrieveDataSetMember {} {}", dataSetName, memberName);

		return retrieve(String.format("%s(%s)", dataSetName, memberName));
	}

	public void recallDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("recallDataSetMember {} {}", dataSetName, memberName);

		throw new ZOSUnsupportedOperationException("");
	}

	public ByteArrayOutputStream retrieveSequentialDataSet(String dataSetName) throws ConnectionException {
		LOG.debug("retrieveSequentialDataSet {}", dataSetName);

		return retrieve(dataSetName);
	}

	public void saveDataSetMember(String dataSetName, String memberName, InputStream contents) throws ConnectionException {
		LOG.debug("saveDataSetMember {} {} {}", dataSetName, memberName, contents);

		try (Reader r = new InputStreamReader(contents)) {
			dsnWrite.write(dataSetName, memberName, IOUtils.toString(r));
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}
	}

	public void deleteDataSet(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("deleteDataSet {} {}", dataSetName, memberName);

		try {
			if (dataSetName == null) {
				response = dsnDelete.delete(memberName);
			} else {
				response = dsnDelete.delete(dataSetName, memberName);
			}

			LOG.debug("dsnDelete {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	public void createDataSet(String dataSetName, DataSetArguments dataSetArguments) throws ConnectionException {
		LOG.debug("createDataSet {} {}", dataSetName, dataSetArguments);

		// Convert alcunit com.ibm.cics.zos.model.DataSet$SpaceUnits
		String alcunit;

		switch (dataSetArguments.spaceUnits) {
		case "CYLINDERS":
			alcunit = "CYL";
			break;
		case "TRACKS":
			alcunit = "TRK";
			break;
		case "BLOCKS":
		default:
			alcunit = null;
			break;
		}

		CreateParams createParams = new CreateParams.Builder()
				.dsorg(dataSetArguments.datasetType.startsWith("PDS") ? "PO" : dataSetArguments.datasetType)
				.dsntype("PDSE".equals(dataSetArguments.datasetType) ? "LIBRARY" : null)
				.alcunit(alcunit)
				.primary((int) dataSetArguments.primaryAllocation)
				.secondary((int) dataSetArguments.secondaryAllocation)
				.dirblk((int) dataSetArguments.directoryBlocks)
				.recfm(dataSetArguments.recordFormat)
				.blksize((int) dataSetArguments.blockSize)
				.lrecl((int) dataSetArguments.recordLength).build();

		try {
			response = dsnCreate.create(dataSetName, createParams);

			LOG.debug("dsnCreate {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

	}

	public ZOSConnectionResponse getDataSet(String dataSetName) throws ConnectionException {
		LOG.debug("getDataSet {}", dataSetName);

		Optional<ZOSConnectionResponse> first = getDataSets(dataSetName).stream().filter(s -> dataSetName.equals(s.getAttribute("FILE_NAME"))).findFirst();

		if (first.isPresent()) {
			return first.get();
		} else {
			throw new ZOSFileNotFoundException(dataSetName, null);
		}
	}

	public ZOSConnectionResponse getDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("getDataSetMember {} {}", dataSetName, memberName);

		Optional<ZOSConnectionResponse> first = getDataSetMembers(dataSetName).stream().filter(s -> memberName.equals(s.getAttribute("NAME"))).findFirst();

		if (first.isPresent()) {
			return first.get();
		} else {
			throw new ZOSFileNotFoundException(dataSetName, memberName);
		}
	}

	public ZOSConnectionResponse createDataSetMember(String dataSetName, String memberName) throws ConnectionException {
		LOG.debug("createDataSetMember {} {}", dataSetName, memberName);

		try {
			response = dsnWrite.write(dataSetName, memberName, "");
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		LOG.debug("dsnWrite {}", response);

		ZOSConnectionResponse cr = new ZOSConnectionResponse();

		cr.addAttribute(IZOSConstants.NAME, memberName);

		return cr;
	}

	public void createDataSet(String dataSetName, String basedOnDataSetPath, InputStream contents) throws ConnectionException {
		LOG.debug("createDataSet {} {} {}", dataSetName, basedOnDataSetPath, contents);

		CopyParams copyParams = new CopyParams.Builder().fromDataSet(basedOnDataSetPath).toDataSet(dataSetName).build();

		try {
			response = dsnCopy.copyCommon(copyParams);

			LOG.debug("dsnCopy {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		try (Reader r = new InputStreamReader(contents)) {
			dsnWrite.write(dataSetName, IOUtils.toString(r));
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}
	}

	private ByteArrayOutputStream retrieve(String dataSetName) throws ConnectionException {
		DownloadParams params = new DownloadParams.Builder().build();

		try (InputStream is = dsnGet.get(dataSetName, params); 
				ByteArrayOutputStream os = new ByteArrayOutputStream()) {
			IOUtils.copy(is, os);

			return os;
		} catch (IOException | ZosmfRequestException e) {
			throw new ConnectionException(String.format("Cannot retrieve %s", dataSetName), e);
		}
	}

	private List<ZOSConnectionResponse> getDataSets(String pattern) throws ConnectionException {
		List<Dataset> items;

		try {
			items = dsnList.getDatasets(pattern, new ListParams.Builder().attribute(AttributeType.BASE).build());
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		List<ZOSConnectionResponse> result = new ArrayList<>(items.size());

		for (Dataset item : items) {
			ZOSConnectionResponse cr = new ZOSConnectionResponse();
			cr.addAttribute(IZOSConstants.FILE_NAME, item.getDsname().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_BLOCK_SIZE, item.getBlksz().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_EXT, item.getExtx().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_RECORD_LENGTH, item.getLrectl().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_REFERRED_DATE, item.getRdate().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_RECORD_FORMAT, item.getRecfm().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_ALLOCATED, item.getUsed().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_VOLUME, item.getVol().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_CREATION_DATE, item.getCdate().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_SIZE, item.getSizex().orElse(ZoweConnection.UNKNOWN));

			if ("YES".equals(item.getMigr().orElse("NO"))) {
				cr.addAttribute(IZOSConstants.FILE_UNAVAILABLE, IZOSConstants.Unavailable.Migrated);
			}

			String dsorg = item.getDsorg().orElse(ZoweConnection.UNKNOWN);

			if ("VS".equals(dsorg)) {
				cr.addAttribute(IZOSConstants.FILE_DSORG, "VSAM");

				if (cr.getAttribute(IZOSConstants.FILE_NAME).endsWith(".DATA")) {
					cr.addAttribute(IZOSConstants.FILE_VSAM_DATA, true);
				}

				if (cr.getAttribute(IZOSConstants.FILE_NAME).endsWith(".INDEX")) {
					cr.addAttribute(IZOSConstants.FILE_VSAM_INDEX, true);
				}
			} else {
				cr.addAttribute(IZOSConstants.FILE_DSORG, dsorg);
			}

			result.add(cr);
		}

		return result;
	}

	private List<ZOSConnectionResponse> getMembers(String dataSetName) throws ConnectionException {
		List<Member> items;

		try {
			items = dsnList.getMembers(dataSetName, new ListParams.Builder().attribute(AttributeType.MEMBER).build());
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		List<ZOSConnectionResponse> result = new ArrayList<>(items.size());

		for (Member item : items) {
			ZOSConnectionResponse cr = new ZOSConnectionResponse();

			cr.addAttribute(IZOSConstants.FILE_PARENTPATH, dataSetName);
			cr.addAttribute(IZOSConstants.NAME, item.getMember().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_CREATION_DATE, item.getC4date().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_CHANGED_DATE, item.getM4date().orElse(ZoweConnection.UNKNOWN));
			cr.addAttribute(IZOSConstants.FILE_MOD, item.getVers().orElse(0L));

			result.add(cr);
		}

		return result;
	}
}
