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

/**
 * Base exception for ad-hoc poll failures related to configuration
 * or service resolution errors.
 */
public class AdhocPollException extends RuntimeException {

    public AdhocPollException(String message) {
        super(message);
    }

    public AdhocPollException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when the requested service is not monitored on the given node.
     */
    public static class ServiceNotFound extends AdhocPollException {
        public ServiceNotFound(int nodeId, String serviceName) {
            super(String.format("No monitored service '%s' found on node %d", serviceName, nodeId));
        }
    }

    /**
     * Thrown when no poller package matches the resolved IP address and service.
     */
    public static class PackageNotFound extends AdhocPollException {
        public PackageNotFound(String ipAddress, String serviceName) {
            super(String.format("No poller package matches IP %s for service '%s'", ipAddress, serviceName));
        }
    }

    /**
     * Thrown when no service monitor class is configured for the service name.
     */
    public static class MonitorNotFound extends AdhocPollException {
        public MonitorNotFound(String serviceName) {
            super(String.format("No monitor class configured for service '%s'", serviceName));
        }
    }

    /**
     * Thrown when a rate limit is exceeded (global concurrency, per-service
     * cooldown, or per-user rate limit).
     */
    public static class RateLimitExceeded extends AdhocPollException {
        private final long retryAfterSeconds;

        public RateLimitExceeded(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        /** Suggested number of seconds the caller should wait before retrying. */
        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
