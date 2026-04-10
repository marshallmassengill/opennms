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

import org.opennms.netmgt.model.OnmsServiceSilence;

/**
 * Service for managing time-bounded notification silences on monitored services.
 *
 * <p>When a service is silenced, events and alarms are still created, but
 * Notifd will suppress notification dispatch for that service.</p>
 */
public interface ServiceSilenceService {

    /**
     * Create a silence for the given node and service lasting the specified duration.
     *
     * @param nodeId      the node ID
     * @param serviceName the service name
     * @param durationMs  silence duration in milliseconds
     * @param createdBy   username of the person creating the silence
     * @return the created silence record
     */
    OnmsServiceSilence silence(int nodeId, String serviceName, long durationMs, String createdBy);

    /**
     * Cancel any active silence for the given node and service by setting
     * its end time to now.
     *
     * @param nodeId      the node ID
     * @param serviceName the service name
     */
    void cancel(int nodeId, String serviceName);

    /**
     * Get the active silence for the given node and service, or null if none.
     *
     * @param nodeId      the node ID
     * @param serviceName the service name
     * @return the active silence, or null
     */
    OnmsServiceSilence getActiveSilence(int nodeId, String serviceName);

    /**
     * Check whether a service is currently silenced. This is the hot-path
     * method called from the notification pipeline.
     *
     * @param nodeId      the node ID (as string from event)
     * @param ipAddr      the IP address
     * @param serviceName the service name
     * @return true if notifications for this service should be suppressed
     */
    boolean isSilenced(String nodeId, String ipAddr, String serviceName);
}
