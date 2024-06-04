/*********************************************************************
* Copyright (c) 12.04.2024 Thomas Zierer
*
* This program and the accompanying materials are made
* available under the terms of the Eclipse Public License 2.0
* which is available at https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
**********************************************************************/
package de.tgmz.aqua.connection.zowe;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import de.tgmz.aqua.connection.zowe.connection.ZoweUssConnection;

public class Activator extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "de.tgmz.aqua.connection.zowe";
	// The shared instance
	private static Activator plugin;

	/**
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}
	
	private static void setDefault(Activator aPlugin) {
		Activator.plugin = aPlugin;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.ui.plugin.AbstractUIPlugin#start(org.osgi.framework.BundleContext)
	 */
	//CHECKSTYLE DISABLE IllegalThrows
    // start() und stop() müssen super... aufrufen und beide deklarieren ... throws Excpetion.
	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		setDefault(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.ui.plugin.AbstractUIPlugin#stop(org.osgi.framework.BundleContext)
	 */
	@Override
	public void stop(BundleContext context) throws Exception {
		setDefault(null);
		ZoweUssConnection.DF.remove();
		super.stop(context);
	}
}
