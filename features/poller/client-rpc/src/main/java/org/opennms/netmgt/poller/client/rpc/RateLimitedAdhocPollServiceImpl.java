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

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.opennms.netmgt.poller.AdhocPollException;
import org.opennms.netmgt.poller.AdhocPollResult;
import org.opennms.netmgt.poller.AdhocPollService;
import org.opennms.netmgt.poller.RateLimitedAdhocPollService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator around {@link AdhocPollService} that enforces rate limits.
 *
 * <p>Three layers of protection:</p>
 * <ol>
 *   <li><b>Global concurrency</b> — limits simultaneous ad-hoc polls system-wide</li>
 *   <li><b>Per-service cooldown</b> — prevents re-polling the same node+service too frequently</li>
 *   <li><b>Per-user rate limit</b> — sliding window limiting polls per user per minute</li>
 * </ol>
 *
 * <p>The base {@link AdhocPollService} methods (without username) enforce global
 * and per-service limits but skip per-user checks, suitable for admin paths
 * like the Karaf shell.</p>
 *
 * <p>Configuration via system properties (set in opennms.properties):</p>
 * <ul>
 *   <li>{@code org.opennms.netmgt.poller.adhoc.maxConcurrent} — max concurrent polls (default 5)</li>
 *   <li>{@code org.opennms.netmgt.poller.adhoc.cooldownSeconds} — per-service cooldown (default 30)</li>
 *   <li>{@code org.opennms.netmgt.poller.adhoc.perUserPerMinute} — per-user polls per minute (default 10)</li>
 * </ul>
 */
public class RateLimitedAdhocPollServiceImpl implements RateLimitedAdhocPollService {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitedAdhocPollServiceImpl.class);

    static final String PROP_MAX_CONCURRENT = "org.opennms.netmgt.poller.adhoc.maxConcurrent";
    static final String PROP_COOLDOWN_SECONDS = "org.opennms.netmgt.poller.adhoc.cooldownSeconds";
    static final String PROP_PER_USER_PER_MINUTE = "org.opennms.netmgt.poller.adhoc.perUserPerMinute";

    static final int DEFAULT_MAX_CONCURRENT = 5;
    static final int DEFAULT_COOLDOWN_SECONDS = 30;
    static final int DEFAULT_PER_USER_PER_MINUTE = 10;

    private static final long ONE_MINUTE_MS = TimeUnit.MINUTES.toMillis(1);

    private final AdhocPollService delegate;
    private final Semaphore concurrencySemaphore;
    private final int cooldownSeconds;
    private final int perUserPerMinute;

    // nodeId:serviceName → last poll timestamp (epoch ms)
    private final ConcurrentHashMap<String, Long> serviceCooldowns = new ConcurrentHashMap<>();

    // username → deque of poll timestamps within the sliding window
    private final ConcurrentHashMap<String, Deque<Long>> userWindows = new ConcurrentHashMap<>();

    public RateLimitedAdhocPollServiceImpl(AdhocPollService delegate) {
        this(delegate,
             Integer.getInteger(PROP_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT),
             Integer.getInteger(PROP_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS),
             Integer.getInteger(PROP_PER_USER_PER_MINUTE, DEFAULT_PER_USER_PER_MINUTE));
    }

    // Visible for testing
    RateLimitedAdhocPollServiceImpl(AdhocPollService delegate,
                                    int maxConcurrent, int cooldownSeconds, int perUserPerMinute) {
        this.delegate = Objects.requireNonNull(delegate);
        this.concurrencySemaphore = new Semaphore(maxConcurrent);
        this.cooldownSeconds = cooldownSeconds;
        this.perUserPerMinute = perUserPerMinute;

        LOG.info("Ad-hoc poll rate limiter initialized: maxConcurrent={}, cooldownSeconds={}, perUserPerMinute={}",
                maxConcurrent, cooldownSeconds, perUserPerMinute);
    }

    // --- RateLimitedAdhocPollService methods (with username) ---

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, String serviceName, String username) {
        Objects.requireNonNull(username, "username must not be null");
        checkUserRateLimit(username);
        checkServiceCooldown(nodeId, serviceName);
        return executeWithConcurrencyLimit(() -> delegate.poll(nodeId, serviceName), nodeId, serviceName);
    }

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, InetAddress ipAddress, String serviceName, String username) {
        Objects.requireNonNull(username, "username must not be null");
        checkUserRateLimit(username);
        checkServiceCooldown(nodeId, serviceName);
        return executeWithConcurrencyLimit(() -> delegate.poll(nodeId, ipAddress, serviceName), nodeId, serviceName);
    }

    // --- AdhocPollService methods (without username, skip per-user check) ---

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, String serviceName) {
        checkServiceCooldown(nodeId, serviceName);
        return executeWithConcurrencyLimit(() -> delegate.poll(nodeId, serviceName), nodeId, serviceName);
    }

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, InetAddress ipAddress, String serviceName) {
        checkServiceCooldown(nodeId, serviceName);
        return executeWithConcurrencyLimit(() -> delegate.poll(nodeId, ipAddress, serviceName), nodeId, serviceName);
    }

    @Override
    public List<String> findAllMatchingPackages(int nodeId, String serviceName) {
        // No rate limiting for read-only config queries
        return delegate.findAllMatchingPackages(nodeId, serviceName);
    }

    // --- Rate limiting logic ---

    private void checkUserRateLimit(String username) {
        final long now = System.currentTimeMillis();
        final Deque<Long> window = userWindows.computeIfAbsent(username, k -> new ArrayDeque<>());

        synchronized (window) {
            // Evict timestamps older than 1 minute
            while (!window.isEmpty() && (now - window.peekFirst()) > ONE_MINUTE_MS) {
                window.pollFirst();
            }

            if (window.size() >= perUserPerMinute) {
                final long oldestInWindow = window.peekFirst();
                final long retryAfterMs = ONE_MINUTE_MS - (now - oldestInWindow);
                final long retryAfterSeconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(retryAfterMs) + 1);
                throw new AdhocPollException.RateLimitExceeded(
                        String.format("Per-user rate limit exceeded for '%s': %d polls per minute allowed",
                                username, perUserPerMinute),
                        retryAfterSeconds);
            }

            window.addLast(now);
        }
    }

    private void checkServiceCooldown(int nodeId, String serviceName) {
        final String key = nodeId + ":" + serviceName;
        final long now = System.currentTimeMillis();
        final long cooldownMs = TimeUnit.SECONDS.toMillis(cooldownSeconds);

        final Long lastPoll = serviceCooldowns.get(key);
        if (lastPoll != null) {
            final long elapsed = now - lastPoll;
            if (elapsed < cooldownMs) {
                final long remainingSeconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(cooldownMs - elapsed) + 1);
                throw new AdhocPollException.RateLimitExceeded(
                        String.format("Service '%s' on node %d was polled %d seconds ago; cooldown is %d seconds",
                                serviceName, nodeId,
                                TimeUnit.MILLISECONDS.toSeconds(elapsed), cooldownSeconds),
                        remainingSeconds);
            }
        }
    }

    private CompletableFuture<AdhocPollResult> executeWithConcurrencyLimit(
            PollSupplier pollSupplier, int nodeId, String serviceName) {

        if (!concurrencySemaphore.tryAcquire()) {
            throw new AdhocPollException.RateLimitExceeded(
                    "Maximum concurrent ad-hoc polls reached. Try again shortly.", 1);
        }

        final String cooldownKey = nodeId + ":" + serviceName;
        try {
            final CompletableFuture<AdhocPollResult> future = pollSupplier.get();
            return future.whenComplete((result, ex) -> {
                concurrencySemaphore.release();
                if (ex == null) {
                    // Record successful poll timestamp for cooldown
                    serviceCooldowns.put(cooldownKey, System.currentTimeMillis());
                }
            });
        } catch (Exception e) {
            concurrencySemaphore.release();
            throw e;
        }
    }

    @FunctionalInterface
    private interface PollSupplier {
        CompletableFuture<AdhocPollResult> get();
    }
}
