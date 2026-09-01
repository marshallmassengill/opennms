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
package org.opennms.netmgt.enlinkd.persistence.impl;

import java.net.InetAddress;
import java.util.Date;
import java.util.List;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.enlinkd.persistence.api.LldpLinkDao;
import org.opennms.netmgt.dao.hibernate.AbstractDaoHibernate;
import org.opennms.netmgt.enlinkd.model.LldpLink;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.springframework.util.Assert;

/**
 * <p>IpInterfaceDaoHibernate class.</p>
 *
 * @author antonio
 */
public class LldpLinkDaoHibernate extends AbstractDaoHibernate<LldpLink, Integer>  implements LldpLinkDao {

    /**
     * <p>Constructor for IpInterfaceDaoHibernate.</p>
     */
    public LldpLinkDaoHibernate() {
        super(LldpLink.class);
    }

    /** {@inheritDoc} */
    @Override
    public LldpLink get(OnmsNode node, Integer lldpRemLocalPortNum, Integer lldpRemIndex) {
        return findUnique("from LldpLink as lldpLink where lldpLink.node = ?1 and lldpLink.lldpRemLocalPortNum = ?2 and lldpRemIndex = ?3", node, lldpRemLocalPortNum, lldpRemIndex);
    }

    /** {@inheritDoc} */
    @Override
    public LldpLink get(Integer nodeId, Integer lldpRemLocalPortNum, Integer lldpRemIndex) {
        Assert.notNull(nodeId, "nodeId cannot be null");
        Assert.notNull(lldpRemLocalPortNum, "lldpRemLocalPortNum cannot be null");
        Assert.notNull(lldpRemIndex, "lldpRemIndex cannot be null");
        return findUnique("from LldpLink as lldpLink where lldpLink.node.id = ?1 and lldpLink.lldpRemLocalPortNum = ?2 and lldpLink.lldpRemIndex = ?3", nodeId, lldpRemLocalPortNum, lldpRemIndex);
    }
    
    /** {@inheritDoc} */
    @Override
    public List<LldpLink> findByNodeId(Integer nodeId) {
        Assert.notNull(nodeId, "nodeId cannot be null");
        return find("from LldpLink lldpLink where lldpLink.node.id = ?1", nodeId);
    }

    @Override
    public void deleteByNodeIdOlderThen(Integer nodeId, Date now) {
        bulkDelete("delete from LldpLink lldpLink where lldpLink.node.id = ?1 and lldpLink.lldpLinkLastPollTime < ?2",
                nodeId, now);
    }

   @Override
   public void deleteByNodeId(Integer nodeId) {
       bulkDelete("delete from LldpLink lldpLink where lldpLink.node.id = ?1 ",
                                         nodeId);
    }

    @Override
    public void deleteAll() {
        bulkDelete("delete from LldpLink");
    }

    @Override
    public Integer getIfIndex(Integer nodeid, String portId) {
        Assert.notNull(nodeid, "nodeId may not be null");
        Assert.notNull(portId, "portId may not be null");

        List<?> ifaces=
                findObjects(OnmsSnmpInterface.class, "SELECT snmpIf FROM OnmsSnmpInterface AS snmpIf WHERE snmpIf.node.id = ?1 AND (LOWER(snmpIf.ifDescr) = LOWER(?2) OR LOWER(snmpIf.ifName) = LOWER(?3) OR snmpIf.physAddr = ?4)",
                       nodeid,
                        portId,
                       portId,
                        portId
                );
        if (ifaces.size() == 1) {
            return ((OnmsSnmpInterface) ifaces.iterator().next()).getIfIndex();
        }
        final InetAddress ipAddr;
        try {
            ipAddr = InetAddressUtils.addr(portId);
        } catch (IllegalArgumentException e) {
            // portId is not an IP address, so no ip-interface match is possible
            return -1;
        }
        if (ipAddr == null) {
            return -1;
        }
        List<Integer> ifindexes = findObjects(Integer.class, "SELECT ipIf.snmpInterface.ifIndex FROM OnmsIpInterface AS ipIf WHERE ipIf.node.id = ?1 AND ipIf.ipAddress = ?2", nodeid, ipAddr);
        if (ifindexes.size() == 1 && ifindexes.get(0) != null) {
            return ifindexes.get(0);
        }
        return -1;
    }

}
