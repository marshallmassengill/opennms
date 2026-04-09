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
package org.opennms.netmgt.poller;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Extended {@link AdhocPollService} that enforces rate limits and accepts
 * a username for per-user throttling.
 *
 * <p>The REST API layer should use this interface to pass the authenticated
 * username. The base {@link AdhocPollService} methods (without username)
 * skip the per-user rate limit check, making them suitable for admin-only
 * paths like the Karaf shell.</p>
 */
public interface RateLimitedAdhocPollService extends AdhocPollService {

    /**
     * Execute an ad-hoc poll with per-user rate limiting.
     *
     * @param nodeId      the node ID
     * @param serviceName the service name
     * @param username    the authenticated username for per-user rate limiting
     * @return a future that completes with the full poll result
     * @throws AdhocPollException.RateLimitExceeded if any rate limit is exceeded
     */
    CompletableFuture<AdhocPollResult> poll(int nodeId, String serviceName, String username);

    /**
     * Execute an ad-hoc poll for a specific IP address with per-user rate limiting.
     *
     * @param nodeId      the node ID
     * @param ipAddress   the specific IP address to poll
     * @param serviceName the service name
     * @param username    the authenticated username for per-user rate limiting
     * @return a future that completes with the full poll result
     * @throws AdhocPollException.RateLimitExceeded if any rate limit is exceeded
     */
    CompletableFuture<AdhocPollResult> poll(int nodeId, InetAddress ipAddress, String serviceName, String username);
}
