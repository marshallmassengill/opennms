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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.config.PollerConfig;
import org.opennms.netmgt.config.poller.Package;
import org.opennms.netmgt.config.poller.Service;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsMonitoringLocation;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsServiceType;
import org.opennms.netmgt.poller.AdhocPollException;
import org.opennms.netmgt.poller.AdhocPollResult;
import org.opennms.netmgt.poller.LocationAwarePollerClient;
import org.opennms.netmgt.poller.PollStatus;
import org.opennms.netmgt.poller.PollerRequestBuilder;
import org.opennms.netmgt.poller.PollerResponse;
import org.opennms.netmgt.poller.ServiceMonitorAdaptor;
import org.opennms.netmgt.poller.ServiceMonitorLocator;

public class AdhocPollServiceImplTest {

    private static final int NODE_ID = 1;
    private static final String NODE_LABEL = "test-node";
    private static final String LOCATION = "Default";
    private static final String IP_ADDRESS = "192.168.1.1";
    private static final String SERVICE_NAME = "ICMP";
    private static final String MONITOR_CLASS = "org.opennms.netmgt.poller.monitors.IcmpMonitor";
    private static final String PACKAGE_NAME = "example1";

    @Mock
    private LocationAwarePollerClient locationAwarePollerClient;

    @Mock
    private MonitoredServiceDao monitoredServiceDao;

    @Mock
    private PollerConfig pollerConfig;

    @Mock
    private SessionUtils sessionUtils;

    @InjectMocks
    private AdhocPollServiceImpl adhocPollService;

    private InetAddress ipAddress;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        ipAddress = InetAddressUtils.addr(IP_ADDRESS);

        // SessionUtils should execute the supplier directly
        when(sessionUtils.withReadOnlyTransaction(any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });
    }

    @Test
    public void testPollHappyPath() throws Exception {
        // Set up service resolution
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        // Set up config resolution
        Package pkg = createPackage();
        when(pollerConfig.findPackageForService(IP_ADDRESS, SERVICE_NAME)).thenReturn(pkg);

        ServiceMonitorLocator locator = mock(ServiceMonitorLocator.class);
        when(locator.getServiceLocatorKey()).thenReturn(MONITOR_CLASS);
        when(pollerConfig.getServiceMonitorLocator(SERVICE_NAME)).thenReturn(Optional.of(locator));

        // Set up poll execution
        PollStatus expectedStatus = PollStatus.available(42.0);
        PollerRequestBuilder builder = mockPollerRequestBuilder(expectedStatus);
        when(locationAwarePollerClient.poll()).thenReturn(builder);

        // Execute
        AdhocPollResult result = adhocPollService.poll(NODE_ID, SERVICE_NAME).get();

        // Verify result
        assertNotNull(result);
        assertEquals(PollStatus.SERVICE_AVAILABLE, result.getPollStatus().getStatusCode());
        assertEquals(42.0, result.getPollStatus().getResponseTime(), 0.001);
        assertEquals(MONITOR_CLASS, result.getMonitorClassName());
        assertEquals(PACKAGE_NAME, result.getPackageName());
        assertEquals(IP_ADDRESS, result.getIpAddress());
        assertEquals(NODE_ID, result.getNodeId());
        assertEquals(SERVICE_NAME, result.getServiceName());
        assertNotNull(result.getExecutionTimestamp());

        // Verify no adaptors were added
        verify(builder, never()).withAdaptor(any(ServiceMonitorAdaptor.class));
    }

    @Test
    public void testPollWithExplicitIpAddress() throws Exception {
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.get(NODE_ID, ipAddress, SERVICE_NAME)).thenReturn(monSvc);

        Package pkg = createPackage();
        when(pollerConfig.findPackageForService(IP_ADDRESS, SERVICE_NAME)).thenReturn(pkg);

        ServiceMonitorLocator locator = mock(ServiceMonitorLocator.class);
        when(locator.getServiceLocatorKey()).thenReturn(MONITOR_CLASS);
        when(pollerConfig.getServiceMonitorLocator(SERVICE_NAME)).thenReturn(Optional.of(locator));

        PollStatus expectedStatus = PollStatus.available(10.0);
        PollerRequestBuilder builder = mockPollerRequestBuilder(expectedStatus);
        when(locationAwarePollerClient.poll()).thenReturn(builder);

        AdhocPollResult result = adhocPollService.poll(NODE_ID, ipAddress, SERVICE_NAME).get();

        assertNotNull(result);
        assertEquals(IP_ADDRESS, result.getIpAddress());

        // Verify it used get() not getPrimaryService()
        verify(monitoredServiceDao).get(NODE_ID, ipAddress, SERVICE_NAME);
        verify(monitoredServiceDao, never()).getPrimaryService(any(Integer.class), anyString());
    }

    @Test(expected = AdhocPollException.ServiceNotFound.class)
    public void testPollServiceNotFound() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(null);

        adhocPollService.poll(NODE_ID, SERVICE_NAME);
    }

    @Test(expected = AdhocPollException.PackageNotFound.class)
    public void testPollPackageNotFound() {
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);
        when(pollerConfig.findPackageForService(IP_ADDRESS, SERVICE_NAME)).thenReturn(null);

        adhocPollService.poll(NODE_ID, SERVICE_NAME);
    }

    @Test(expected = AdhocPollException.MonitorNotFound.class)
    public void testPollMonitorNotFound() {
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        Package pkg = createPackage();
        when(pollerConfig.findPackageForService(IP_ADDRESS, SERVICE_NAME)).thenReturn(pkg);
        when(pollerConfig.getServiceMonitorLocator(SERVICE_NAME)).thenReturn(Optional.empty());

        adhocPollService.poll(NODE_ID, SERVICE_NAME);
    }

    @Test
    public void testPollServiceDown() throws Exception {
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        Package pkg = createPackage();
        when(pollerConfig.findPackageForService(IP_ADDRESS, SERVICE_NAME)).thenReturn(pkg);

        ServiceMonitorLocator locator = mock(ServiceMonitorLocator.class);
        when(locator.getServiceLocatorKey()).thenReturn(MONITOR_CLASS);
        when(pollerConfig.getServiceMonitorLocator(SERVICE_NAME)).thenReturn(Optional.of(locator));

        PollStatus downStatus = PollStatus.down("Connection refused");
        PollerRequestBuilder builder = mockPollerRequestBuilder(downStatus);
        when(locationAwarePollerClient.poll()).thenReturn(builder);

        AdhocPollResult result = adhocPollService.poll(NODE_ID, SERVICE_NAME).get();

        assertEquals(PollStatus.SERVICE_UNAVAILABLE, result.getPollStatus().getStatusCode());
        assertEquals("Connection refused", result.getPollStatus().getReason());
    }

    @Test
    public void testFindAllMatchingPackages() {
        OnmsMonitoredService monSvc = createMonitoredService();
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(monSvc);

        Package pkg1 = new Package();
        pkg1.setName("package1");

        Package pkg2 = new Package();
        pkg2.setName("package2");

        Package perspectiveOnly = new Package();
        perspectiveOnly.setName("perspective-pkg");
        perspectiveOnly.setPerspectiveOnly(true);

        Package noMatch = new Package();
        noMatch.setName("no-match");

        when(pollerConfig.getPackages()).thenReturn(Arrays.asList(pkg1, pkg2, perspectiveOnly, noMatch));
        when(pollerConfig.isServiceInPackageAndEnabled(eq(SERVICE_NAME), eq(pkg1))).thenReturn(true);
        when(pollerConfig.isServiceInPackageAndEnabled(eq(SERVICE_NAME), eq(pkg2))).thenReturn(true);
        when(pollerConfig.isServiceInPackageAndEnabled(eq(SERVICE_NAME), eq(noMatch))).thenReturn(false);
        when(pollerConfig.isInterfaceInPackage(eq(IP_ADDRESS), eq(pkg1))).thenReturn(true);
        when(pollerConfig.isInterfaceInPackage(eq(IP_ADDRESS), eq(pkg2))).thenReturn(true);

        List<String> packages = adhocPollService.findAllMatchingPackages(NODE_ID, SERVICE_NAME);

        assertEquals(2, packages.size());
        assertTrue(packages.contains("package1"));
        assertTrue(packages.contains("package2"));
    }

    @Test(expected = AdhocPollException.ServiceNotFound.class)
    public void testFindAllMatchingPackagesServiceNotFound() {
        when(monitoredServiceDao.getPrimaryService(NODE_ID, SERVICE_NAME)).thenReturn(null);

        adhocPollService.findAllMatchingPackages(NODE_ID, SERVICE_NAME);
    }

    // --- Helper methods ---

    private OnmsMonitoredService createMonitoredService() {
        OnmsMonitoringLocation location = new OnmsMonitoringLocation();
        location.setLocationName(LOCATION);

        OnmsNode node = new OnmsNode();
        node.setId(NODE_ID);
        node.setLabel(NODE_LABEL);
        node.setLocation(location);

        OnmsIpInterface iface = new OnmsIpInterface();
        iface.setIpAddress(ipAddress);
        iface.setNode(node);

        OnmsServiceType serviceType = new OnmsServiceType(SERVICE_NAME);

        OnmsMonitoredService monSvc = new OnmsMonitoredService(iface, serviceType);
        monSvc.setStatus("A");

        return monSvc;
    }

    private Package createPackage() {
        Service svc = new Service();
        svc.setName(SERVICE_NAME);
        svc.setInterval(300000L);
        svc.setStatus("on");

        Package pkg = new Package();
        pkg.setName(PACKAGE_NAME);
        pkg.addService(svc);

        return pkg;
    }

    private PollerRequestBuilder mockPollerRequestBuilder(PollStatus pollStatus) {
        PollerRequestBuilder builder = mock(PollerRequestBuilder.class);
        when(builder.withService(any())).thenReturn(builder);
        when(builder.withMonitorClassName(anyString())).thenReturn(builder);
        when(builder.withAttributes(any())).thenReturn(builder);
        when(builder.withPatternVariables(any())).thenReturn(builder);
        when(builder.withAdaptor(any())).thenReturn(builder);
        when(builder.withTimeToLive(any())).thenReturn(builder);
        when(builder.withSystemId(any())).thenReturn(builder);

        PollerResponse response = mock(PollerResponse.class);
        when(response.getPollStatus()).thenReturn(pollStatus);
        when(builder.execute()).thenReturn(CompletableFuture.completedFuture(response));

        return builder;
    }
}
