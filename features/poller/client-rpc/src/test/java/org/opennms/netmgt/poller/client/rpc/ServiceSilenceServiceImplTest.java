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
package org.opennms.netmgt.poller.client.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.ServiceSilenceDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsMonitoringLocation;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsServiceSilence;
import org.opennms.netmgt.model.OnmsServiceType;

public class ServiceSilenceServiceImplTest {

    private static final int NODE_ID = 1;
    private static final String SERVICE_NAME = "ICMP";
    private static final String IP_ADDRESS = "192.168.1.1";

    @Mock
    private ServiceSilenceDao serviceSilenceDao;

    @Mock
    private MonitoredServiceDao monitoredServiceDao;

    @Mock
    private SessionUtils sessionUtils;

    @InjectMocks
    private ServiceSilenceServiceImpl service;

    private OnmsMonitoredService monSvc;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // SessionUtils executes suppliers directly
        when(sessionUtils.withTransaction(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(sessionUtils.withReadOnlyTransaction(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

        monSvc = createMonitoredService();
    }

    @Test
    public void testSilenceCreatesRecord() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(null);

        OnmsServiceSilence result = service.silence(NODE_ID, SERVICE_NAME, 1800000L, "admin");

        assertNotNull(result);
        assertEquals(monSvc, result.getMonitoredService());
        assertEquals("admin", result.getCreatedBy());
        assertTrue(result.getEndTime().getTime() > result.getStartTime().getTime());

        // Verify duration is approximately correct (30 minutes = 1800000ms)
        long duration = result.getEndTime().getTime() - result.getStartTime().getTime();
        assertTrue(Math.abs(duration - 1800000L) < 1000); // within 1 second

        verify(serviceSilenceDao).save(any(OnmsServiceSilence.class));
    }

    @Test
    public void testSilenceCancelsExistingFirst() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        OnmsServiceSilence existing = new OnmsServiceSilence(monSvc,
                new Date(System.currentTimeMillis() - 60000),
                new Date(System.currentTimeMillis() + 60000),
                "admin");
        existing.setId(99);
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(existing);

        service.silence(NODE_ID, SERVICE_NAME, 1800000L, "admin");

        // Existing silence should have been cancelled (endTime set to now)
        verify(serviceSilenceDao).saveOrUpdate(existing);
        assertTrue(existing.getEndTime().getTime() <= System.currentTimeMillis());

        // New silence should have been created
        verify(serviceSilenceDao).save(any(OnmsServiceSilence.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSilenceServiceNotFound() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(null);
        service.silence(NODE_ID, SERVICE_NAME, 1800000L, "admin");
    }

    @Test
    public void testCancel() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        OnmsServiceSilence active = new OnmsServiceSilence(monSvc,
                new Date(System.currentTimeMillis() - 60000),
                new Date(System.currentTimeMillis() + 60000),
                "admin");
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(active);

        service.cancel(NODE_ID, SERVICE_NAME);

        verify(serviceSilenceDao).saveOrUpdate(active);
        assertTrue(active.getEndTime().getTime() <= System.currentTimeMillis());
    }

    @Test
    public void testCancelNoActiveIsNoop() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(null);

        service.cancel(NODE_ID, SERVICE_NAME);

        verify(serviceSilenceDao, never()).saveOrUpdate(any());
    }

    @Test
    public void testCancelServiceNotFoundIsNoop() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(null);

        service.cancel(NODE_ID, SERVICE_NAME);

        verify(serviceSilenceDao, never()).saveOrUpdate(any());
    }

    @Test
    public void testGetActiveSilence() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        OnmsServiceSilence active = new OnmsServiceSilence(monSvc,
                new Date(), new Date(System.currentTimeMillis() + 60000), "admin");
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(active);

        OnmsServiceSilence result = service.getActiveSilence(NODE_ID, SERVICE_NAME);
        assertNotNull(result);
        assertEquals(active, result);
    }

    @Test
    public void testGetActiveSilenceReturnsNull() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);
        when(serviceSilenceDao.findActiveByMonitoredService(eq(monSvc.getId()), any(Date.class))).thenReturn(null);

        assertNull(service.getActiveSilence(NODE_ID, SERVICE_NAME));
    }

    @Test
    public void testIsSilencedTrue() {
        OnmsServiceSilence active = new OnmsServiceSilence();
        when(serviceSilenceDao.findActiveByNodeAndService(eq(NODE_ID), eq(IP_ADDRESS), eq(SERVICE_NAME), any(Date.class)))
                .thenReturn(Collections.singletonList(active));

        assertTrue(service.isSilenced(String.valueOf(NODE_ID), IP_ADDRESS, SERVICE_NAME));
    }

    @Test
    public void testIsSilencedFalse() {
        when(serviceSilenceDao.findActiveByNodeAndService(eq(NODE_ID), eq(IP_ADDRESS), eq(SERVICE_NAME), any(Date.class)))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isSilenced(String.valueOf(NODE_ID), IP_ADDRESS, SERVICE_NAME));
    }

    @Test
    public void testIsSilencedWithNullNodeId() {
        assertFalse(service.isSilenced(null, IP_ADDRESS, SERVICE_NAME));
    }

    @Test
    public void testIsSilencedWithNullIpAddr() {
        assertFalse(service.isSilenced(String.valueOf(NODE_ID), null, SERVICE_NAME));
    }

    @Test
    public void testIsSilencedWithNullServiceName() {
        assertFalse(service.isSilenced(String.valueOf(NODE_ID), IP_ADDRESS, null));
    }

    @Test
    public void testIsSilencedWithInvalidNodeId() {
        assertFalse(service.isSilenced("notanumber", IP_ADDRESS, SERVICE_NAME));
    }

    // --- Helper ---

    private OnmsMonitoredService createMonitoredService() {
        OnmsMonitoringLocation location = new OnmsMonitoringLocation();
        location.setLocationName("Default");

        OnmsNode node = new OnmsNode();
        node.setId(NODE_ID);
        node.setLabel("test-node");
        node.setLocation(location);

        OnmsIpInterface iface = new OnmsIpInterface();
        iface.setIpAddress(InetAddressUtils.addr(IP_ADDRESS));
        iface.setNode(node);

        OnmsServiceType serviceType = new OnmsServiceType(SERVICE_NAME);

        OnmsMonitoredService svc = new OnmsMonitoredService(iface, serviceType);
        svc.setId(42);
        svc.setStatus("A");
        return svc;
    }
}
