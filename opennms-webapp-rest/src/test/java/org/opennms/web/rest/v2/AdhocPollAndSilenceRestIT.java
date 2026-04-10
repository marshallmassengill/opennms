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
package org.opennms.web.rest.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.core.MediaType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.MockLogAppender;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.opennms.core.test.rest.AbstractSpringJerseyRestTestCase;
import org.opennms.netmgt.poller.AdhocPollException;
import org.opennms.netmgt.poller.AdhocPollResult;
import org.opennms.netmgt.poller.PollStatus;
import org.opennms.netmgt.poller.RateLimitedAdhocPollService;
import org.opennms.netmgt.poller.ServiceSilenceService;
import org.opennms.netmgt.model.OnmsServiceSilence;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml",
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-mockConfigManager.xml",
        "classpath*:/META-INF/opennms/component-service.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-databasePopulator.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
        "file:src/main/webapp/WEB-INF/applicationContext-svclayer.xml",
        "file:src/main/webapp/WEB-INF/applicationContext-cxf-common.xml",
        "classpath:/META-INF/opennms/applicationContext-adhoc-poll-test.xml"
})
@JUnitConfigurationEnvironment(systemProperties = "org.opennms.timeseries.strategy=integration")
@JUnitTemporaryDatabase
public class AdhocPollAndSilenceRestIT extends AbstractSpringJerseyRestTestCase {

    public AdhocPollAndSilenceRestIT() {
        super(CXF_REST_V2_CONTEXT_PATH);
    }

    @Autowired
    private RateLimitedAdhocPollService m_adhocPollService;

    @Autowired
    private ServiceSilenceService m_serviceSilenceService;

    @Override
    protected void afterServletStart() throws Exception {
        MockLogAppender.setupLogging(true, "DEBUG");
    }

    @Before
    public void setUp() throws Throwable {
        super.setUp();
        reset(m_adhocPollService, m_serviceSilenceService);
        // Create a node so we have something to poll
        createNode();
    }

    // --- Ad-hoc Poll Tests ---

    @Test
    @JUnitTemporaryDatabase
    public void testPollServiceSuccess() throws Exception {
        AdhocPollResult result = new AdhocPollResult(
                PollStatus.available(25.0),
                "org.opennms.netmgt.poller.monitors.IcmpMonitor",
                "example1", "10.10.10.10", 1, "ICMP", new Date());

        when(m_adhocPollService.poll(anyInt(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));

        MockHttpServletResponse response = sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/1/services/ICMP/poll", "{}", 200);

        String body = response.getContentAsString();
        assertNotNull(body);
        assertTrue(body.contains("ICMP") || body.contains("icmp") || body.contains("monitor"));
    }

    @Test
    @JUnitTemporaryDatabase
    public void testPollServiceNotFound() throws Exception {
        when(m_adhocPollService.poll(anyInt(), anyString(), anyString()))
                .thenThrow(new AdhocPollException.ServiceNotFound(1, "NONEXISTENT"));

        sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/1/services/NONEXISTENT/poll", "{}", 404);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testPollNodeNotFound() throws Exception {
        sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/99999/services/ICMP/poll", "{}", 404);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testPollRateLimited() throws Exception {
        when(m_adhocPollService.poll(anyInt(), anyString(), anyString()))
                .thenThrow(new AdhocPollException.RateLimitExceeded("Rate limit exceeded", 30));

        MockHttpServletResponse response = sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/1/services/ICMP/poll", "{}", 429);

        assertEquals("30", response.getHeader("Retry-After"));
    }

    // --- Silence Tests ---

    @Test
    @JUnitTemporaryDatabase
    public void testCreateSilence() throws Exception {
        OnmsServiceSilence silence = new OnmsServiceSilence();
        silence.setId(1);
        silence.setStartTime(new Date());
        silence.setEndTime(new Date(System.currentTimeMillis() + 1800000));
        silence.setCreatedBy("admin");

        when(m_serviceSilenceService.silence(anyInt(), eq("ICMP"), eq(1800000L), eq("admin")))
                .thenReturn(silence);

        MockHttpServletResponse response = sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/1/services/ICMP/silence", "{\"duration\":1800000}", 201);

        assertNotNull(response.getContentAsString());
        verify(m_serviceSilenceService).silence(anyInt(), eq("ICMP"), eq(1800000L), eq("admin"));
    }

    @Test
    @JUnitTemporaryDatabase
    public void testCreateSilenceMissingDuration() throws Exception {
        sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/1/services/ICMP/silence", "{}", 400);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testCreateSilenceNodeNotFound() throws Exception {
        sendData("POST", MediaType.APPLICATION_JSON,
                "/nodes/99999/services/ICMP/silence", "{\"duration\":1800000}", 404);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testGetActiveSilence() throws Exception {
        OnmsServiceSilence silence = new OnmsServiceSilence();
        silence.setId(1);
        silence.setStartTime(new Date());
        silence.setEndTime(new Date(System.currentTimeMillis() + 1800000));
        silence.setCreatedBy("admin");

        when(m_serviceSilenceService.getActiveSilence(anyInt(), eq("ICMP")))
                .thenReturn(silence);

        sendRequest(GET, "/nodes/1/services/ICMP/silence", 200);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testGetNoActiveSilence() throws Exception {
        when(m_serviceSilenceService.getActiveSilence(anyInt(), eq("ICMP")))
                .thenReturn(null);

        sendRequest(GET, "/nodes/1/services/ICMP/silence", 404);
    }

    @Test
    @JUnitTemporaryDatabase
    public void testCancelSilence() throws Exception {
        sendRequest(DELETE, "/nodes/1/services/ICMP/silence", 204);
        verify(m_serviceSilenceService).cancel(anyInt(), eq("ICMP"));
    }

    @Test
    @JUnitTemporaryDatabase
    public void testCancelSilenceNodeNotFound() throws Exception {
        sendRequest(DELETE, "/nodes/99999/services/ICMP/silence", 404);
    }
}
