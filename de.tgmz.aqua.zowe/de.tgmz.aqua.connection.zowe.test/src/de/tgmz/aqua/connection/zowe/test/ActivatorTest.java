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

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ActivatorTest {
	@Test
	public void testActivator() {
		assertNotNull(de.tgmz.aqua.connection.zowe.Activator.getDefault());
	}
}
