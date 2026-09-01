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
package org.opennms.netmgt.enlinkd.snmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.opennms.netmgt.enlinkd.model.LldpLink;
import org.opennms.netmgt.enlinkd.model.OspfArea;
import org.opennms.netmgt.snmp.SnmpInstId;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpResult;
import org.opennms.netmgt.snmp.SnmpUtils;
import org.opennms.netmgt.snmp.SnmpValue;

/**
 * Rows missing optional columns (lldpRemSysName is an optional TLV,
 * ospfAuthType is obsolete in RFC 4750, isisISAdjNbrExtendedCircID may be
 * omitted) must still convert to model objects instead of throwing
 * NullPointerException.
 */
public class SparseRowTrackerTest {

    private static void add(org.opennms.netmgt.snmp.SnmpRowResult row, SnmpObjId base, SnmpValue value) {
        row.addResult(base, new SnmpResult(base, row.getInstance(), value));
    }

    private static SnmpValue str(String value) {
        return SnmpUtils.getValueFactory().getOctetString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void lldpRemRowWithoutOptionalColumns() {
        final LldpRemTableTracker.LldpRemRow row =
                new LldpRemTableTracker.LldpRemRow(5, new SnmpInstId("0.5.1"));

        add(row, LldpRemTableTracker.LLDP_REM_CHASSIS_ID_OID, str("chassis-1"));
        add(row, LldpRemTableTracker.LLDP_REM_PORT_ID_OID, str("eth0"));
        // no chassis-id subtype, no port-id subtype, no sysname

        assertNull(row.getLldpRemChassisidSubtype());
        assertNull(row.getLldpRemPortidSubtype());
        assertEquals("", row.getLldpRemSysname());

        final LldpLink link = row.getLldpLink();
        assertNotNull(link);
        assertEquals("", link.getLldpRemSysname());
        assertNotNull(link.getLldpRemChassisId());
        assertNotNull(link.getLldpRemPortId());
    }

    @Test
    public void timeTetraLldpRemRowWithoutOptionalColumns() {
        final TimeTetraLldpRemTableTracker.TimeTetraLldpRemRow row =
                new TimeTetraLldpRemTableTracker.TimeTetraLldpRemRow(5, new SnmpInstId("0.10.2.1"));

        add(row, TimeTetraLldpRemTableTracker.TIMETETRA_LLDP_REM_CHASSIS_ID_OID, str("chassis-1"));
        add(row, TimeTetraLldpRemTableTracker.TIMETETRA_LLDP_REM_PORT_ID_OID, str("eth0"));

        assertNull(row.getLldpRemChassisidSubtype());
        assertNull(row.getLldpRemPortidSubtype());
        assertEquals("", row.getLldpRemSysname());

        final LldpLink link = row.getLldpLink();
        assertNotNull(link);
        assertEquals("", link.getLldpRemSysname());
    }

    @Test
    public void ospfAreaRowWithoutAuthType() {
        final OspfAreaTableTracker.OspfAreaRow row =
                new OspfAreaTableTracker.OspfAreaRow(6, new SnmpInstId("0.0.0.0"));

        add(row, OspfAreaTableTracker.OSPF_AREA_ID_OID, SnmpUtils.getValueFactory().getIpAddress(org.opennms.core.utils.InetAddressUtils.addr("0.0.0.0")));
        add(row, OspfAreaTableTracker.OSPF_AREA_BDR_RTR_COUNT_OID, SnmpUtils.getValueFactory().getGauge32(1));
        // no ospfAuthType, no importAsExtern, no asBdrRtrCount, no lsaCount

        assertNull(row.getOspfAuthType());
        assertNull(row.getOspfImportAsExtern());

        final OspfArea area = row.getOspfArea();
        assertNotNull(area);
        assertNull(area.getOspfAuthType());
    }

    @Test
    public void isisAdjRowWithoutExtendedCircId() {
        final IsisISAdjTableTracker.IsIsAdjRow row =
                new IsisISAdjTableTracker.IsIsAdjRow(5, new SnmpInstId("1.1"));

        assertEquals(Integer.valueOf(0), row.getIsisISAdjNbrExtendedCircID());
    }
}
