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

import java.util.Date;
import java.util.Objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Immutable result of an ad-hoc poll execution.
 *
 * <p>Wraps the full {@link PollStatus} (including status code, reason,
 * response time, and all properties) along with metadata about which
 * configuration was resolved to execute the poll.</p>
 *
 * <p>The result carries enough context for a future "apply results"
 * extension to trigger a forced re-poll through Pollerd without
 * re-resolving configuration.</p>
 */
@XmlRootElement(name = "adhoc-poll-result")
@XmlAccessorType(XmlAccessType.NONE)
public class AdhocPollResult {

    private final PollStatus pollStatus;
    private final String monitorClassName;
    private final String packageName;
    private final String ipAddress;
    private final int nodeId;
    private final String serviceName;
    private final Date executionTimestamp;

    // No-arg constructor for JAXB
    @SuppressWarnings("unused")
    private AdhocPollResult() {
        this.pollStatus = null;
        this.monitorClassName = null;
        this.packageName = null;
        this.ipAddress = null;
        this.nodeId = 0;
        this.serviceName = null;
        this.executionTimestamp = null;
    }

    public AdhocPollResult(PollStatus pollStatus, String monitorClassName,
                           String packageName, String ipAddress, int nodeId,
                           String serviceName, Date executionTimestamp) {
        this.pollStatus = Objects.requireNonNull(pollStatus);
        this.monitorClassName = Objects.requireNonNull(monitorClassName);
        this.packageName = Objects.requireNonNull(packageName);
        this.ipAddress = Objects.requireNonNull(ipAddress);
        this.nodeId = nodeId;
        this.serviceName = Objects.requireNonNull(serviceName);
        this.executionTimestamp = Objects.requireNonNull(executionTimestamp);
    }

    /** The full poll status including status code, reason, response time, and all properties. */
    @XmlElement(name = "poll-status")
    public PollStatus getPollStatus() {
        return pollStatus;
    }

    /** The fully-qualified class name of the ServiceMonitor that was used. */
    @XmlAttribute(name = "monitor-class")
    public String getMonitorClassName() {
        return monitorClassName;
    }

    /** The name of the poller package that was matched. */
    @XmlAttribute(name = "package")
    public String getPackageName() {
        return packageName;
    }

    /** The IP address that was polled (resolved from the node if not explicitly specified). */
    @XmlAttribute(name = "ip-address")
    public String getIpAddress() {
        return ipAddress;
    }

    /** The node ID that was polled. */
    @XmlAttribute(name = "node-id")
    public int getNodeId() {
        return nodeId;
    }

    /** The service name that was polled. */
    @XmlAttribute(name = "service")
    public String getServiceName() {
        return serviceName;
    }

    /** When the poll was initiated (distinct from PollStatus timestamp set by the monitor). */
    @XmlAttribute(name = "execution-time")
    public Date getExecutionTimestamp() {
        return executionTimestamp;
    }

    @Override
    public String toString() {
        return String.format("AdhocPollResult[node=%d, ip=%s, service=%s, status=%s, monitor=%s, package=%s]",
                nodeId, ipAddress, serviceName,
                pollStatus != null ? pollStatus.getStatusName() : "null",
                monitorClassName, packageName);
    }
}
