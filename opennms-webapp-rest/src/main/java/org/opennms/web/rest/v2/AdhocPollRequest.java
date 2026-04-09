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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Request body for ad-hoc poll execution.
 *
 * <p>Both flags default to {@code false}. When the request body is omitted
 * entirely, a default instance is used.</p>
 */
@XmlRootElement(name = "adhoc-poll-request")
@XmlAccessorType(XmlAccessType.NONE)
public class AdhocPollRequest {

    /**
     * Whether to feed the poll result back into the monitoring state machine
     * (events, outages, alarms). Requires ROLE_ADMIN.
     *
     * <p>Currently accepted but not yet implemented — reserved for the
     * "apply results" extension.</p>
     */
    @XmlAttribute(name = "update-status")
    private boolean updateStatus = false;

    /**
     * When {@code updateStatus} is true, whether to suppress alarm and
     * notification workflows while still updating monitoring state.
     *
     * <p>Currently accepted but not yet implemented — reserved for the
     * "apply results" extension.</p>
     */
    @XmlAttribute(name = "suppress-notifications")
    private boolean suppressNotifications = false;

    public AdhocPollRequest() {
    }

    public boolean isUpdateStatus() {
        return updateStatus;
    }

    public void setUpdateStatus(boolean updateStatus) {
        this.updateStatus = updateStatus;
    }

    public boolean isSuppressNotifications() {
        return suppressNotifications;
    }

    public void setSuppressNotifications(boolean suppressNotifications) {
        this.suppressNotifications = suppressNotifications;
    }
}
