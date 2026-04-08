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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for executing on-demand, diagnostic polls against monitored services.
 *
 * <p>Ad-hoc polls are <b>side-effect-free</b>: they do not trigger state
 * transitions, generate events, open/resolve outages, or persist latency
 * data. The full {@link PollStatus} is returned to the caller with all
 * diagnostic detail (status, reason, response time, properties).</p>
 *
 * <p>Given a node ID and service name, the service automatically resolves
 * the IP address (from the node's primary SNMP interface), the matching
 * poller package, monitor class, and parameters — the same way Pollerd
 * does when scheduling.</p>
 *
 * <h3>Future extension: Apply Results</h3>
 * <p>A planned extension will add an optional "apply results" flag that,
 * when set, triggers Pollerd to force an immediate re-poll of the service
 * through its normal state-transition path (events, outages, alarms).
 * This allows a network engineer to verify a fix and immediately update
 * monitoring state. The current side-effect-free design is intentionally
 * structured to support this as an additive change.</p>
 */
public interface AdhocPollService {

    /**
     * Execute an on-demand poll for the given node and service,
     * auto-resolving the IP address from the node's primary SNMP interface.
     *
     * @param nodeId      the node ID
     * @param serviceName the service name (e.g., "ICMP", "HTTP")
     * @return a future that completes with the full poll result
     * @throws AdhocPollException.ServiceNotFound if the service is not monitored on this node
     * @throws AdhocPollException.PackageNotFound if no poller package matches
     * @throws AdhocPollException.MonitorNotFound if no monitor class is configured
     */
    CompletableFuture<AdhocPollResult> poll(int nodeId, String serviceName);

    /**
     * Execute an on-demand poll for a specific IP address on a node.
     *
     * <p>Use this overload when the service exists on multiple interfaces
     * and you want to poll a specific one rather than the primary.</p>
     *
     * @param nodeId      the node ID
     * @param ipAddress   the specific IP address to poll
     * @param serviceName the service name
     * @return a future that completes with the full poll result
     * @throws AdhocPollException.ServiceNotFound if the service is not monitored at this IP on this node
     * @throws AdhocPollException.PackageNotFound if no poller package matches
     * @throws AdhocPollException.MonitorNotFound if no monitor class is configured
     */
    CompletableFuture<AdhocPollResult> poll(int nodeId, InetAddress ipAddress, String serviceName);

    /**
     * Returns all poller package names that match the given node and service.
     *
     * <p>Useful for debugging configuration issues where a service might
     * appear in multiple packages. The package used for actual polling is
     * the last match (consistent with {@code PollerConfig.findPackageForService}).</p>
     *
     * @param nodeId      the node ID
     * @param serviceName the service name
     * @return list of matching package names, possibly empty
     * @throws AdhocPollException.ServiceNotFound if the service is not monitored on this node
     */
    List<String> findAllMatchingPackages(int nodeId, String serviceName);
}
