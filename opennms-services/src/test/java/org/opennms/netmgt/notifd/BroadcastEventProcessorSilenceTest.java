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
package org.opennms.netmgt.notifd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;
import org.opennms.netmgt.config.NotificationManager;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.poller.ServiceSilenceService;
import org.opennms.netmgt.xml.event.Event;

/**
 * Tests the silence integration in BroadcastEventProcessor.continueWithNotice().
 *
 * Uses reflection to access the private method and inject the mock
 * ServiceSilenceService, since BroadcastEventProcessor is a final class.
 */
public class BroadcastEventProcessorSilenceTest {

    private BroadcastEventProcessor processor;
    private NotificationManager notificationManager;
    private ServiceSilenceService serviceSilenceService;

    @Before
    public void setUp() throws Exception {
        processor = new BroadcastEventProcessor();

        notificationManager = mock(NotificationManager.class);
        serviceSilenceService = mock(ServiceSilenceService.class);

        // Inject the NotificationManager
        setField(processor, "m_notificationManager", notificationManager);

        // Inject the ServiceSilenceService
        setField(processor, "m_serviceSilenceService", serviceSilenceService);
    }

    @Test
    public void testContinueWithNotice_NotSilenced() throws Exception {
        when(notificationManager.getServiceNoticeStatus("1", "192.168.1.1", "ICMP")).thenReturn("Y");
        when(serviceSilenceService.isSilenced("1", "192.168.1.1", "ICMP")).thenReturn(false);

        Event event = buildEvent(1L, "192.168.1.1", "ICMP");
        boolean result = invokeContinueWithNotice(event);

        assertTrue("Notification should continue when service is not silenced", result);
    }

    @Test
    public void testContinueWithNotice_Silenced() throws Exception {
        when(notificationManager.getServiceNoticeStatus("1", "192.168.1.1", "ICMP")).thenReturn("Y");
        when(serviceSilenceService.isSilenced("1", "192.168.1.1", "ICMP")).thenReturn(true);

        Event event = buildEvent(1L, "192.168.1.1", "ICMP");
        boolean result = invokeContinueWithNotice(event);

        assertFalse("Notification should be suppressed when service is silenced", result);
    }

    @Test
    public void testContinueWithNotice_NotifyDisabled_SilenceNotChecked() throws Exception {
        // When notify status is not 'Y', silence check should not even be reached
        when(notificationManager.getServiceNoticeStatus("1", "192.168.1.1", "ICMP")).thenReturn("N");

        Event event = buildEvent(1L, "192.168.1.1", "ICMP");
        boolean result = invokeContinueWithNotice(event);

        assertFalse("Notification should not continue when notify is 'N'", result);
        verify(serviceSilenceService, never()).isSilenced(anyString(), anyString(), anyString());
    }

    @Test
    public void testContinueWithNotice_NullSilenceService() throws Exception {
        // If ServiceSilenceService is not available, notifications should continue
        setField(processor, "m_serviceSilenceService", null);

        when(notificationManager.getServiceNoticeStatus("1", "192.168.1.1", "ICMP")).thenReturn("Y");

        Event event = buildEvent(1L, "192.168.1.1", "ICMP");
        boolean result = invokeContinueWithNotice(event);

        assertTrue("Notification should continue when silence service is null", result);
    }

    @Test
    public void testContinueWithNotice_SilenceCheckThrows() throws Exception {
        when(notificationManager.getServiceNoticeStatus("1", "192.168.1.1", "ICMP")).thenReturn("Y");
        when(serviceSilenceService.isSilenced("1", "192.168.1.1", "ICMP"))
                .thenThrow(new RuntimeException("DB connection failed"));

        Event event = buildEvent(1L, "192.168.1.1", "ICMP");
        boolean result = invokeContinueWithNotice(event);

        assertTrue("Notification should continue when silence check throws", result);
    }

    @Test
    public void testContinueWithNotice_NullFields() throws Exception {
        // Events with null node/interface/service should bypass all checks and continue
        Event event = new EventBuilder("uei.opennms.org/test", "test").getEvent();
        boolean result = invokeContinueWithNotice(event);

        assertTrue("Notification should continue when event has null fields", result);
        verify(serviceSilenceService, never()).isSilenced(anyString(), anyString(), anyString());
    }

    // --- Helpers ---

    private Event buildEvent(long nodeId, String ipAddr, String serviceName) {
        return new EventBuilder("uei.opennms.org/test", "test")
                .setNodeid(nodeId)
                .setInterface(org.opennms.core.utils.InetAddressUtils.addr(ipAddr))
                .setService(serviceName)
                .getEvent();
    }

    private boolean invokeContinueWithNotice(Event event) throws Exception {
        Method method = BroadcastEventProcessor.class.getDeclaredMethod("continueWithNotice", Event.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(processor, event);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
