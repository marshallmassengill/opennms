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

import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.opennms.core.utils.LldpUtils.LldpPortIdSubType;
import org.opennms.netmgt.snmp.SnmpUtils;
import org.opennms.netmgt.snmp.SnmpValue;

/**
 * A displayable decimal local port id ("50") must decode to 50, not be
 * re-parsed as hex (80); a non-hex string must not abort the collection
 * with NumberFormatException.
 */
public class TimeTetraLldpPortIdDecodeTest {

    private static SnmpValue str(String value) {
        return SnmpUtils.getValueFactory().getOctetString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void displayableDecimalPortIdIsNotHexParsed() {
        assertEquals("50", LldpSnmpUtils.decodeTimeTetraLldpPortId(LldpPortIdSubType.LLDP_PORTID_SUBTYPE_LOCAL, str("50")));
    }

    @Test
    public void rawValueIsHexParsed() {
        // a raw one-byte octet string 0x21 has hex display "21" and is not displayable-decimal
        final SnmpValue raw = SnmpUtils.getValueFactory().getOctetString(new byte[]{0x21});
        assertEquals("33", LldpSnmpUtils.decodeTimeTetraLldpPortId(LldpPortIdSubType.LLDP_PORTID_SUBTYPE_LOCAL, raw));
    }

    @Test
    public void nonHexStringDoesNotThrow() {
        assertEquals(LldpSnmpUtils.getDisplayable(str("port-xyz")),
                LldpSnmpUtils.decodeTimeTetraLldpPortId(LldpPortIdSubType.LLDP_PORTID_SUBTYPE_LOCAL, str("port-xyz")));
    }
}
