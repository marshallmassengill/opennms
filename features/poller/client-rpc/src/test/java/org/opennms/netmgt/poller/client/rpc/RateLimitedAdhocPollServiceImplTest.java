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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.Test;
import org.opennms.netmgt.poller.AdhocPollException;
import org.opennms.netmgt.poller.AdhocPollResult;
import org.opennms.netmgt.poller.AdhocPollService;
import org.opennms.netmgt.poller.PollStatus;

public class RateLimitedAdhocPollServiceImplTest {

    private static final int NODE_ID = 1;
    private static final String SERVICE_NAME = "ICMP";
    private static final String USERNAME = "testuser";

    private AdhocPollService delegate;
    private CompletableFuture<AdhocPollResult> successFuture;

    @Before
    public void setUp() {
        delegate = mock(AdhocPollService.class);

        AdhocPollResult result = new AdhocPollResult(
                PollStatus.available(10.0),
                "org.opennms.netmgt.poller.monitors.IcmpMonitor",
                "example1", "192.168.1.1", NODE_ID, SERVICE_NAME, new Date());
        successFuture = CompletableFuture.completedFuture(result);

        when(delegate.poll(anyInt(), anyString())).thenReturn(successFuture);
    }

    @Test
    public void testHappyPath() throws Exception {
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 5, 0, 100);

        AdhocPollResult result = service.poll(NODE_ID, SERVICE_NAME, USERNAME).get();
        assertNotNull(result);
        assertEquals(PollStatus.SERVICE_AVAILABLE, result.getPollStatus().getStatusCode());
    }

    @Test
    public void testGlobalConcurrencyLimit() {
        // maxConcurrent=1, use a never-completing future to hold the semaphore
        CompletableFuture<AdhocPollResult> neverComplete = new CompletableFuture<>();
        when(delegate.poll(anyInt(), anyString())).thenReturn(neverComplete);

        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 1, 0, 100);

        // First poll acquires the single permit
        service.poll(NODE_ID, SERVICE_NAME, USERNAME);

        // Second poll should be rejected
        try {
            service.poll(NODE_ID, "HTTP", "otheruser");
            fail("Expected RateLimitExceeded");
        } catch (AdhocPollException.RateLimitExceeded e) {
            assertTrue(e.getMessage().contains("concurrent"));
            assertTrue(e.getRetryAfterSeconds() > 0);
        }
    }

    @Test
    public void testPerServiceCooldown() throws Exception {
        // cooldownSeconds=60
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 5, 60, 100);

        // First poll succeeds and completes (records cooldown timestamp)
        service.poll(NODE_ID, SERVICE_NAME, USERNAME).get();

        // Immediate re-poll of same node+service should be rejected
        try {
            service.poll(NODE_ID, SERVICE_NAME, USERNAME);
            fail("Expected RateLimitExceeded");
        } catch (AdhocPollException.RateLimitExceeded e) {
            assertTrue(e.getMessage().contains("cooldown"));
            assertTrue(e.getRetryAfterSeconds() > 0);
        }
    }

    @Test
    public void testPerServiceCooldownDifferentService() throws Exception {
        // cooldownSeconds=60
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 5, 60, 100);

        // Poll ICMP
        service.poll(NODE_ID, SERVICE_NAME, USERNAME).get();

        // Poll HTTP on same node — should succeed (different service)
        AdhocPollResult result = service.poll(NODE_ID, "HTTP", USERNAME).get();
        assertNotNull(result);
    }

    @Test
    public void testPerUserRateLimit() throws Exception {
        // perUserPerMinute=3
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 10, 0, 3);

        // Three polls succeed
        service.poll(NODE_ID, "SVC1", USERNAME).get();
        service.poll(NODE_ID, "SVC2", USERNAME).get();
        service.poll(NODE_ID, "SVC3", USERNAME).get();

        // Fourth poll exceeds per-user limit
        try {
            service.poll(NODE_ID, "SVC4", USERNAME);
            fail("Expected RateLimitExceeded");
        } catch (AdhocPollException.RateLimitExceeded e) {
            assertTrue(e.getMessage().contains("Per-user"));
            assertTrue(e.getRetryAfterSeconds() > 0);
        }
    }

    @Test
    public void testPerUserLimitDifferentUsers() throws Exception {
        // perUserPerMinute=2
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 10, 0, 2);

        // User A exhausts their limit
        service.poll(NODE_ID, "SVC1", "userA").get();
        service.poll(NODE_ID, "SVC2", "userA").get();

        // User B should still work
        AdhocPollResult result = service.poll(NODE_ID, "SVC1", "userB").get();
        assertNotNull(result);
    }

    @Test
    public void testNoUsernameSkipsPerUserCheck() throws Exception {
        // perUserPerMinute=1
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 10, 0, 1);

        // Use the no-username overload (Karaf shell path) — should not enforce per-user limit
        service.poll(NODE_ID, "SVC1").get();
        service.poll(NODE_ID, "SVC2").get();
        service.poll(NODE_ID, "SVC3").get();

        // All should succeed — per-user limit is not applied
        verify(delegate, times(3)).poll(anyInt(), anyString());
    }

    @Test
    public void testSemaphoreReleasedOnDelegateFailure() {
        // maxConcurrent=1, delegate throws immediately
        when(delegate.poll(anyInt(), anyString())).thenThrow(new AdhocPollException.ServiceNotFound(1, "ICMP"));

        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 1, 0, 100);

        try {
            service.poll(NODE_ID, SERVICE_NAME, USERNAME);
            fail("Expected ServiceNotFound");
        } catch (AdhocPollException.ServiceNotFound e) {
            // expected
        }

        // Semaphore should be released — next poll should not be blocked by concurrency limit
        when(delegate.poll(anyInt(), anyString())).thenReturn(successFuture);
        try {
            service.poll(NODE_ID, SERVICE_NAME, "otheruser");
            // Should not throw RateLimitExceeded for concurrency
        } catch (AdhocPollException.RateLimitExceeded e) {
            fail("Semaphore should have been released after delegate failure");
        }
    }

    @Test
    public void testSemaphoreReleasedOnAsyncFailure() throws Exception {
        // maxConcurrent=1, future completes exceptionally
        CompletableFuture<AdhocPollResult> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("poll failed"));
        when(delegate.poll(anyInt(), anyString())).thenReturn(failedFuture);

        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 1, 0, 100);

        CompletableFuture<AdhocPollResult> result = service.poll(NODE_ID, SERVICE_NAME, USERNAME);

        // Wait for it to complete (with exception)
        try {
            result.get();
            fail("Expected exception");
        } catch (Exception e) {
            // expected
        }

        // Semaphore should be released — next poll should work
        when(delegate.poll(anyInt(), anyString())).thenReturn(successFuture);
        AdhocPollResult nextResult = service.poll(NODE_ID, "HTTP", "otheruser").get();
        assertNotNull(nextResult);
    }

    @Test
    public void testFindAllMatchingPackagesNotRateLimited() {
        when(delegate.findAllMatchingPackages(NODE_ID, SERVICE_NAME))
                .thenReturn(java.util.Arrays.asList("pkg1", "pkg2"));

        // Even with very restrictive limits, config queries should pass through
        RateLimitedAdhocPollServiceImpl service = new RateLimitedAdhocPollServiceImpl(delegate, 0, 999, 0);

        assertEquals(2, service.findAllMatchingPackages(NODE_ID, SERVICE_NAME).size());
    }
}
