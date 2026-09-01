/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.enlinkd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashSet;

import org.junit.Test;
import org.opennms.netmgt.enlinkd.service.api.Bridge;
import org.opennms.netmgt.enlinkd.service.api.BridgeForwardingTable;
import org.opennms.netmgt.enlinkd.service.api.BridgePort;
import org.opennms.netmgt.enlinkd.service.api.BridgePortWithMacs;

/**
 * getPort()/getRootPort() must return null for a port without learned
 * FDB entries instead of throwing NoSuchElementException, which used to
 * escape the bridge-domain calculation's BridgeTopologyException handling.
 */
public class BridgeForwardingTableGetPortTest {

    @Test
    public void getPortReturnsNullInsteadOfThrowing() {
        final Bridge bridge = new Bridge(1);
        final BridgeForwardingTable bft = new BridgeForwardingTable(bridge, new HashSet<>());

        assertNull(bft.getPort(1));
        assertNull(bft.getPort(null));
        assertNull(bft.getRootPort());

        final BridgePort port = new BridgePort();
        port.setNodeId(1);
        port.setBridgePort(5);
        bft.getPorttomac().add(new BridgePortWithMacs(port, new HashSet<>()));

        assertEquals(port, bft.getPort(5));
        assertNull(bft.getPort(6));

        bridge.setRootPort(5);
        assertEquals(port, bft.getRootPort());
    }
}
