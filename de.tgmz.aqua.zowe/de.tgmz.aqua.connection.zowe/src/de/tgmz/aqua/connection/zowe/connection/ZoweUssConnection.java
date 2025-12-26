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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.OptionalInt;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cics.common.util.StringUtil;
import com.ibm.cics.core.comm.ConnectionException;
import com.ibm.cics.zos.comm.IZOSConstants;
import com.ibm.cics.zos.comm.IZOSConstants.FileType;
import com.ibm.cics.zos.comm.ZOSConnectionResponse;

import zowe.client.sdk.core.ZosConnection;
import zowe.client.sdk.rest.Response;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.zosfiles.uss.input.UssChangeModeInputData;
import zowe.client.sdk.zosfiles.uss.input.UssCreateInputData;
import zowe.client.sdk.zosfiles.uss.input.UssGetInputData;
import zowe.client.sdk.zosfiles.uss.input.UssListInputData;
import zowe.client.sdk.zosfiles.uss.methods.UssChangeMode;
import zowe.client.sdk.zosfiles.uss.methods.UssCreate;
import zowe.client.sdk.zosfiles.uss.methods.UssDelete;
import zowe.client.sdk.zosfiles.uss.methods.UssGet;
import zowe.client.sdk.zosfiles.uss.methods.UssList;
import zowe.client.sdk.zosfiles.uss.methods.UssWrite;
import zowe.client.sdk.zosfiles.uss.model.UnixFile;
import zowe.client.sdk.zosfiles.uss.types.CreateType;

public class ZoweUssConnection {
	public static final ThreadLocal<DateFormat> DF = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"));

	private static final Logger LOG = LoggerFactory.getLogger(ZoweUssConnection.class);

	private Response response;

	private UssList ussList;
	private UssGet ussGet;
	private UssDelete ussDelete;
	private UssCreate ussCreate;
	private UssWrite ussWrite;
	private UssChangeMode ussChangeMode;

	public ZoweUssConnection(ZosConnection connection) {
		ussList = new UssList(connection);
		ussGet = new UssGet(connection);
		ussDelete = new UssDelete(connection);
		ussCreate = new UssCreate(connection);
		ussWrite = new UssWrite(connection);
		ussChangeMode = new UssChangeMode(connection);
	}

	public List<ZOSConnectionResponse> getHFSChildren(String aPath, boolean includeHiddenFiles) throws ConnectionException {
		LOG.debug("getHFSChildren {}, {}", aPath, includeHiddenFiles);

		String path = normalizePath(aPath);

		List<UnixFile> items;

		try {
			items = ussList.getFiles(new UssListInputData.Builder().path(path).depth(1).build());
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		List<ZOSConnectionResponse> result = new ArrayList<>(items.size());

		for (UnixFile item : items) {
			String name = item.getName();
			
			if (!StringUtil.isEmpty(name)) { 
				ZOSConnectionResponse cr = new ZOSConnectionResponse();

				cr.addAttribute(IZOSConstants.HFS_PARENT_PATH, aPath);
				cr.addAttributeDontTrim(IZOSConstants.NAME, name);
				cr.addAttribute(IZOSConstants.HFS_SIZE, item.getSize());
				cr.addAttribute(IZOSConstants.HFS_USER, item.getUser());
				cr.addAttribute(IZOSConstants.HFS_GROUP, item.getGroup());

				cr.addAttribute(IZOSConstants.HFS_LAST_USED_DATE, getMTime(item));

				String mode = item.getMode();

				cr.addAttribute(IZOSConstants.HFS_PERMISSIONS, mode.substring(1));

				String target = item.getTarget();
			
				if (!StringUtil.isEmpty(target)) {
					cr.addAttribute(IZOSConstants.HFS_SYMLINK, Boolean.TRUE);
					cr.addAttribute(IZOSConstants.HFS_LINKPATH, target);

					String itemPath = normalizePath(String.format("%s/%s", path, name));
				
					try {
						ussGet.getCommon(itemPath, new UssGetInputData.Builder().insensitive(false).maxreturnsize(1).search(itemPath).build());
					
						cr.addAttribute(IZOSConstants.HFS_DIRECTORY, Boolean.FALSE);
					} catch (ZosmfRequestException e) {
						cr.addAttribute(IZOSConstants.HFS_DIRECTORY, Boolean.TRUE);
					}
				} else {
					cr.addAttribute(IZOSConstants.HFS_SYMLINK, Boolean.FALSE);
					cr.addAttribute(IZOSConstants.HFS_DIRECTORY, mode.startsWith("d"));
				}

				result.add(cr);
			}
		}

		return result;
	}

	public boolean existsHFS(String aPath) throws ConnectionException {
		LOG.debug("existsHFS {}", aPath);

		UssGetInputData params = new UssGetInputData.Builder().insensitive(false).search(aPath).build();
		
		try {
			response = ussGet.getCommon(aPath, params);

			LOG.debug("ussGet {}", response);
		} catch (ZosmfRequestException e) {
			OptionalInt oStatusCode = e.getResponse().getStatusCode();

			if (oStatusCode.isPresent() && oStatusCode.getAsInt() == 404) {
				return false;
			} else {
				throw new ConnectionException(e);
			}
		}

		return true;
	}

	public boolean existsHFSFile(String aPath, String aName) throws ConnectionException {
		LOG.debug("existsHFSFile {} {}", aPath, aName);

		return existsHFS(String.format("%s/%s", aPath, aName));
	}

	public void createFolderHFS(String aPath) throws ConnectionException {
		LOG.debug("createFolderHFS {}", aPath);

		UssCreateInputData param = new UssCreateInputData(CreateType.DIR, "rwxr-xr-x");
		
		try {
			response = ussCreate.create(aPath, param);

			LOG.debug("ussCreate {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	public void deletePathHFS(String aPath) throws ConnectionException {
		LOG.debug("deletePathHFS {}", aPath);
		try {
			response = ussDelete.delete(aPath, true);

			LOG.debug("ussDelete {}", response);
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	public void saveFileHFS(String aPath, InputStream fileContents, IZOSConstants.FileType aFileType) throws ConnectionException {
		LOG.debug("saveFileHFS {} {} {}", aPath, fileContents, aFileType);

		try {
			byte[] content = IOUtils.toByteArray(fileContents);
			
			if (aFileType == FileType.BINARY) {
				ussWrite.writeBinary(aPath, content);
			} else {
				ussWrite.writeText(aPath, new String(content));
			}
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}
	}

	public void saveFileHFS(String filePath, InputStream fileContents, String charset) throws ConnectionException {
		LOG.debug("saveFileHFS {} {} {}", filePath, fileContents, charset);

		try (InputStreamReader r = new InputStreamReader(fileContents)) {
			ussWrite.writeBinary(filePath, IOUtils.toByteArray(r, Charset.forName(charset)));
		} catch (ZosmfRequestException | IOException e) {
			throw new ConnectionException(e);
		}
	}

	public ByteArrayOutputStream getFileHFS(String aPath, FileType p1) throws ConnectionException {
		LOG.debug("getFileHFS {}, {}", aPath, p1);

		byte[] content;

		try {
			if (p1 == FileType.BINARY) {
				content = ussGet.getBinary(aPath);
			} else {
				content = ussGet.getText(aPath).getBytes();
			}
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream(content.length);

		try (InputStream is = new ByteArrayInputStream(content)) {
			IOUtils.copy(is, baos);
		} catch (IOException e) {
			throw new ConnectionException(e);
		}

		return baos;
	}

	public void changePermissions(String aPath, String octal) throws ConnectionException {
		LOG.debug("changePermissions {} {}", aPath, octal);

		try {
			ussChangeMode.change(aPath, new UssChangeModeInputData.Builder().mode(octal).build());
		} catch (ZosmfRequestException e) {
			throw new ConnectionException(e);
		}
	}

	private String normalizePath(String aPath) {
		// Trailing slash yields "incorrect path"
		String result = aPath.endsWith("/") && aPath.length() > 1 ? aPath.substring(0, aPath.length() - 1) : aPath;

		result = result.replace("//", "/");

		return result.startsWith("/") ? result : "/" + result;
	}

	private Calendar getMTime(UnixFile item) {
		Calendar result = Calendar.getInstance();

		long time = 0L;
		
		String mTime = item.getMtime();

		try {
			time = DF.get().parse(mTime).getTime();
		} catch (ParseException e) {
			LOG.warn("Cannot convert mtime {}", mTime);
		}

		result.setTimeInMillis(time);

		return result;
	}
}
