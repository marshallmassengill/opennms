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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.opennms.netmgt.config.SnmpPeerFactory;
import org.opennms.netmgt.enlinkd.NodeCollectionGroupLldp;
import org.opennms.netmgt.enlinkd.NodeDiscoveryLldp;
import org.opennms.netmgt.enlinkd.model.LldpElement;
import org.opennms.netmgt.enlinkd.model.LldpElementTopologyEntity;
import org.opennms.netmgt.enlinkd.model.LldpLink;
import org.opennms.netmgt.enlinkd.model.LldpLinkTopologyEntity;
import org.opennms.netmgt.enlinkd.service.api.LldpTopologyService;
import org.opennms.netmgt.enlinkd.service.api.Node;
import org.opennms.netmgt.enlinkd.service.api.TopologyConnection;
import org.opennms.netmgt.scheduler.LegacyPriorityExecutor;
import org.opennms.netmgt.snmp.CollectionTracker;
import org.opennms.netmgt.snmp.SnmpAgentConfig;
import org.opennms.netmgt.snmp.SnmpInstId;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpResult;
import org.opennms.netmgt.snmp.SnmpUtils;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.opennms.netmgt.snmp.proxy.SNMPRequestBuilder;
import org.springframework.core.io.ByteArrayResource;

/**
 * Verifies that NodeDiscoveryLldp distinguishes a failed lldpRemTable walk
 * from a successful-but-empty one: reconcile deletes every link that was not
 * stored in the current run, so it must not run after a walk failure.
 */
public class NodeDiscoveryLldpWalkTest {

    private static final String CISCO_SYSOID = ".1.3.6.1.4.1.9.1.1";
    private static final String TIMETETRA_SYSOID = ".1.3.6.1.4.1.6527.1.3.1";

    private FakeSnmpClient m_client;
    private RecordingLldpTopologyService m_service;

    @Before
    public void setUp() {
        final String snmpConfig = "<?xml version=\"1.0\"?>"
                + "<snmp-config xmlns=\"http://xmlns.opennms.org/xsd/config/snmp\""
                + " port=\"161\" retry=\"1\" timeout=\"1800\" read-community=\"public\" version=\"v2c\"/>";
        SnmpPeerFactory.setInstance(new SnmpPeerFactory(new ByteArrayResource(snmpConfig.getBytes(StandardCharsets.UTF_8))));
        m_client = new FakeSnmpClient();
        m_service = new RecordingLldpTopologyService();
    }

    private void runCollection(String sysoid) throws Exception {
        final Node node = new Node(10, "test-node", InetAddress.getByName("192.0.2.1"), sysoid, "test-node", "Default");
        final NodeCollectionGroupLldp group = new NodeCollectionGroupLldp(
                60000, 0, new LegacyPriorityExecutor("test", 1, 10), 0, null, m_client, m_service);
        new NodeDiscoveryLldp(group, node, 0).collect();
    }

    @Test
    public void remTableWalkFailureSkipsReconcile() throws Exception {
        m_client.failRemTable = true;
        runCollection(CISCO_SYSOID);
        assertEquals(1, m_service.storedElements.size());
        assertEquals(0, m_service.reconcileCalls);
    }

    @Test
    public void remTableWalkEmptyReconciles() throws Exception {
        runCollection(CISCO_SYSOID);
        assertEquals(1, m_service.storedElements.size());
        assertEquals(1, m_service.reconcileCalls);
    }

    @Test
    public void timeTetraWalkFailureSkipsReconcile() throws Exception {
        m_client.failTimeTetra = true;
        runCollection(TIMETETRA_SYSOID);
        assertEquals(0, m_service.reconcileCalls);
    }

    @Test
    public void timeTetraWalkEmptyReconciles() throws Exception {
        runCollection(TIMETETRA_SYSOID);
        assertEquals(1, m_service.reconcileCalls);
    }

    private static class FakeSnmpClient implements LocationAwareSnmpClient {

        boolean failRemTable = false;
        boolean failTimeTetra = false;

        @Override
        public <T extends CollectionTracker> SNMPRequestBuilder<T> walk(SnmpAgentConfig agent, T tracker) {
            if (tracker instanceof LldpLocalGroupTracker) {
                feedLocalGroup((LldpLocalGroupTracker) tracker);
                return new FakeBuilder<>(tracker, false);
            }
            if (tracker instanceof TimeTetraLldpRemTableTracker) {
                return new FakeBuilder<>(tracker, failTimeTetra);
            }
            if (tracker instanceof LldpRemTableTracker) {
                return new FakeBuilder<>(tracker, failRemTable);
            }
            return new FakeBuilder<>(tracker, false);
        }

        private void feedLocalGroup(LldpLocalGroupTracker tracker) {
            final SnmpValue subtype = SnmpUtils.getValueFactory().getInt32(4); // macAddress
            final SnmpValue chassisId = SnmpUtils.getValueFactory().getOctetString(new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55});
            final SnmpValue sysname = SnmpUtils.getValueFactory().getOctetString("test-node".getBytes(StandardCharsets.UTF_8));
            tracker.storeResult(new SnmpResult(SnmpObjId.get(LldpLocalGroupTracker.LLDP_LOC_CHASSISID_SUBTYPE_OID), new SnmpInstId(0), subtype));
            tracker.storeResult(new SnmpResult(SnmpObjId.get(LldpLocalGroupTracker.LLDP_LOC_CHASSISID_OID), new SnmpInstId(0), chassisId));
            tracker.storeResult(new SnmpResult(SnmpObjId.get(LldpLocalGroupTracker.LLDP_LOC_SYSNAME_OID), new SnmpInstId(0), sysname));
        }

        @Override
        public SNMPRequestBuilder<List<SnmpResult>> walk(SnmpAgentConfig agent, String... oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<List<SnmpResult>> walk(SnmpAgentConfig agent, SnmpObjId... oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<List<SnmpResult>> walk(SnmpAgentConfig agent, List<SnmpObjId> oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<SnmpValue> get(SnmpAgentConfig agent, String oid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<SnmpValue> get(SnmpAgentConfig agent, SnmpObjId oid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<List<SnmpValue>> get(SnmpAgentConfig agent, String... oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<List<SnmpValue>> get(SnmpAgentConfig agent, SnmpObjId... oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<List<SnmpValue>> get(SnmpAgentConfig agent, List<SnmpObjId> oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SNMPRequestBuilder<SnmpValue> set(SnmpAgentConfig agent, List<SnmpObjId> oids, List<SnmpValue> values) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeBuilder<T> implements SNMPRequestBuilder<T> {

        private final T m_result;
        private final boolean m_fail;

        FakeBuilder(T result, boolean fail) {
            m_result = result;
            m_fail = fail;
        }

        @Override
        public SNMPRequestBuilder<T> withLocation(String location) {
            return this;
        }

        @Override
        public SNMPRequestBuilder<T> withSystemId(String systemId) {
            return this;
        }

        @Override
        public SNMPRequestBuilder<T> withDescription(String description) {
            return this;
        }

        @Override
        public SNMPRequestBuilder<T> withTimeToLive(Long ttlInMs) {
            return this;
        }

        @Override
        public SNMPRequestBuilder<T> withTimeToLive(long duration, TimeUnit unit) {
            return this;
        }

        @Override
        public CompletableFuture<T> execute() {
            final CompletableFuture<T> future = new CompletableFuture<>();
            if (m_fail) {
                future.completeExceptionally(new Exception("simulated SNMP walk failure"));
            } else {
                future.complete(m_result);
            }
            return future;
        }
    }

    private static class RecordingLldpTopologyService implements LldpTopologyService {

        final List<LldpElement> storedElements = new ArrayList<>();
        final List<LldpLink> storedLinks = new ArrayList<>();
        int reconcileCalls = 0;

        @Override
        public void delete(int nodeid) {
        }

        @Override
        public void reconcile(int nodeId, Date now) {
            reconcileCalls++;
        }

        @Override
        public void store(int nodeId, LldpLink link) {
            storedLinks.add(link);
        }

        @Override
        public void store(int nodeId, LldpElement element) {
            storedElements.add(element);
        }

        @Override
        public List<LldpElementTopologyEntity> findAllLldpElements() {
            return new ArrayList<>();
        }

        @Override
        public List<TopologyConnection<LldpLinkTopologyEntity, LldpLinkTopologyEntity>> match() {
            return new ArrayList<>();
        }

        @Override
        public void deletePersistedData() {
        }

        @Override
        public boolean parseUpdates() {
            return false;
        }

        @Override
        public void updatesAvailable() {
        }

        @Override
        public boolean hasUpdates() {
            return false;
        }

        @Override
        public void refresh() {
        }
    }
}
