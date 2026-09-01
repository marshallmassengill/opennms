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

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.enlinkd.model.OspfIf;
import org.opennms.netmgt.enlinkd.model.OspfLink;

/**
 * Matching of an OSPF neighbor to the local interface it is reached over,
 * in particular for unnumbered (address-less) interfaces where the
 * neighbor's ospfNbrAddressLessIndex must equal the local ifIndex.
 */
public class NodeDiscoveryOspfSetLocalPortTest {

    private static OspfIf unnumberedPort(int addressLessIf, String areaId) {
        final OspfIf port = new OspfIf();
        port.setOspfIfAddressLessIf(addressLessIf);
        port.setOspfIfAreaId(InetAddressUtils.addr(areaId));
        return port;
    }

    private static OspfIf numberedPort(String ip, String mask, int ifIndex, String areaId) {
        final OspfIf port = new OspfIf();
        port.setOspfIfAddressLessIf(0);
        port.setOspfIfIpaddress(InetAddressUtils.addr(ip));
        port.setOspfIfNetmask(InetAddressUtils.addr(mask));
        port.setOspfIfIfindex(ifIndex);
        port.setOspfIfAreaId(InetAddressUtils.addr(areaId));
        return port;
    }

    @Test
    public void unnumberedNeighborMatchesInterfaceWithSameIndex() {
        final List<OspfIf> ports = Arrays.asList(
                unnumberedPort(10, "0.0.0.0"),
                unnumberedPort(20, "0.0.0.1"));

        final OspfLink link = new OspfLink();
        link.setOspfRemAddressLessIndex(20);

        NodeDiscoveryOspf.setLocalPort(link, ports);

        assertEquals(Integer.valueOf(20), link.getOspfIfIndex());
        assertEquals(Integer.valueOf(20), link.getOspfAddressLessIndex());
        assertEquals(InetAddressUtils.addr("0.0.0.1"), link.getOspfIfAreaId());
    }

    @Test
    public void unnumberedNeighborWithoutMatchingInterfaceIsLeftUnset() {
        final List<OspfIf> ports = Arrays.asList(
                unnumberedPort(10, "0.0.0.0"),
                unnumberedPort(20, "0.0.0.1"));

        final OspfLink link = new OspfLink();
        link.setOspfRemAddressLessIndex(30);

        NodeDiscoveryOspf.setLocalPort(link, ports);

        assertNull(link.getOspfIfIndex());
        assertNull(link.getOspfIfAreaId());
    }

    @Test
    public void numberedNeighborMatchesBySubnet() {
        final List<OspfIf> ports = Arrays.asList(
                unnumberedPort(10, "0.0.0.0"),
                numberedPort("192.0.2.1", "255.255.255.0", 5, "0.0.0.2"));

        final OspfLink link = new OspfLink();
        link.setOspfRemAddressLessIndex(0);
        link.setOspfRemIpAddr(InetAddressUtils.addr("192.0.2.9"));

        NodeDiscoveryOspf.setLocalPort(link, ports);

        assertEquals(Integer.valueOf(5), link.getOspfIfIndex());
        assertEquals(InetAddressUtils.addr("192.0.2.1"), link.getOspfIpAddr());
        assertEquals(InetAddressUtils.addr("0.0.0.2"), link.getOspfIfAreaId());
    }
}
