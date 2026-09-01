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

import org.junit.Test;
import org.opennms.netmgt.model.NetworkBuilder;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.SnmpInterfaceBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises every branch of LldpLinkDao.getIfIndex(): resolution via the
 * snmp-interface attributes, via an ip address whose interface has an snmp
 * interface, via an ip address whose interface has none (formerly a
 * ClassCastException), and a port id that matches nothing.
 */
public class LldpLinkDaoGetIfIndexIT extends EnLinkdBuilderITCase {

    @Test
    public void getIfIndexResolvesPortIdShapes() {
        final NetworkBuilder nb = new NetworkBuilder();
        nb.addNode("getifindex").setForeignSource("linkd").setForeignId("getifindex").setType(OnmsNode.NodeType.ACTIVE);
        final SnmpInterfaceBuilder snmpbuilder = nb.addSnmpInterface(101).setIfName("eth0").setIfDescr("eth0").setIfType(6).setPhysAddr("001e58a6aed7");
        nb.addInterface("192.0.2.1", snmpbuilder.getSnmpInterface()).setIsSnmpPrimary("P").setIsManaged("M");
        nb.addInterface("192.0.2.2").setIsSnmpPrimary("N").setIsManaged("M");
        final OnmsNode node = nb.getCurrentNode();
        new TransactionTemplate(m_transactionManager).execute(status -> {
            m_nodeDao.save(node);
            m_nodeDao.flush();
            return null;
        });
        final int nodeId = node.getId();

        assertEquals(Integer.valueOf(101), m_lldpLinkDao.getIfIndex(nodeId, "eth0"));
        assertEquals(Integer.valueOf(101), m_lldpLinkDao.getIfIndex(nodeId, "192.0.2.1"));
        assertEquals(Integer.valueOf(-1), m_lldpLinkDao.getIfIndex(nodeId, "192.0.2.2"));
        assertEquals(Integer.valueOf(-1), m_lldpLinkDao.getIfIndex(nodeId, "no-such-port"));
    }
}
