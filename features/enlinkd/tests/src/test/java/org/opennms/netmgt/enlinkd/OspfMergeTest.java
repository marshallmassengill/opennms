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

import java.net.InetAddress;
import java.util.Date;

import org.junit.Test;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.enlinkd.model.OspfArea;
import org.opennms.netmgt.enlinkd.model.OspfElement;
import org.opennms.netmgt.enlinkd.model.OspfElement.TruthValue;

/**
 * Field-by-field checks of the OSPF merge methods that run on every re-poll.
 */
public class OspfMergeTest {

    @Test
    public void ospfElementMergeKeepsDistinctBorderRouterFlags() {
        final InetAddress routerId = InetAddressUtils.addr("192.0.2.1");

        final OspfElement persisted = new OspfElement();
        persisted.setOspfRouterId(routerId);
        persisted.setOspfBdrRtrStatus(TruthValue.FALSE);
        persisted.setOspfASBdrRtrStatus(TruthValue.FALSE);

        // an area border router that is not an AS border router
        final OspfElement collected = new OspfElement();
        collected.setOspfRouterId(routerId);
        collected.setOspfBdrRtrStatus(TruthValue.TRUE);
        collected.setOspfASBdrRtrStatus(TruthValue.FALSE);

        persisted.merge(collected);

        assertEquals(TruthValue.TRUE, persisted.getOspfBdrRtrStatus());
        assertEquals(TruthValue.FALSE, persisted.getOspfASBdrRtrStatus());
    }

    @Test
    public void ospfAreaMergePreservesCreateTime() {
        final InetAddress areaId = InetAddressUtils.addr("0.0.0.0");
        final Date firstSeen = new Date(1000L);

        final OspfArea persisted = new OspfArea();
        persisted.setOspfAreaId(areaId);
        persisted.setOspfAreaCreateTime(firstSeen);

        final OspfArea collected = new OspfArea();
        collected.setOspfAreaId(areaId);
        final Date polled = collected.getOspfAreaCreateTime();

        persisted.merge(collected);

        assertEquals(firstSeen, persisted.getOspfAreaCreateTime());
        assertEquals(polled, persisted.getOspfAreaLastPollTime());
    }
}
