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

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.opennms.netmgt.enlinkd.model.BridgeBridgeLink;
import org.opennms.netmgt.enlinkd.model.BridgeMacLink;
import org.opennms.netmgt.enlinkd.model.CdpElement;
import org.opennms.netmgt.enlinkd.model.CdpLink;
import org.opennms.netmgt.enlinkd.model.IpNetToMedia;
import org.opennms.netmgt.enlinkd.model.IsIsElement;
import org.opennms.netmgt.enlinkd.model.IsIsLink;
import org.opennms.netmgt.enlinkd.model.LldpLink;
import org.opennms.netmgt.enlinkd.model.OspfElement;
import org.opennms.netmgt.enlinkd.model.OspfLink;

/**
 * toString() on an unsaved entity (no node attached yet) must not throw:
 * collectors log entities before attaching the node.
 */
public class EntityToStringTest {

    @Test
    public void toStringToleratesUnsavedEntities() {
        assertNotNull(new LldpLink().toString());
        assertNotNull(new BridgeBridgeLink().toString());
        assertNotNull(new BridgeMacLink().toString());
        assertNotNull(new CdpLink().toString());
        assertNotNull(new CdpElement().toString());
        assertNotNull(new IsIsElement().toString());
        assertNotNull(new IsIsLink().toString());
        assertNotNull(new OspfElement().toString());
        assertNotNull(new OspfLink().toString());
        assertNotNull(new IpNetToMedia().toString());
    }
}
