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

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.ServiceSilenceDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsServiceSilence;
import org.opennms.netmgt.poller.ServiceSilenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class ServiceSilenceServiceImpl implements ServiceSilenceService {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceSilenceServiceImpl.class);

    @Autowired
    private ServiceSilenceDao serviceSilenceDao;

    @Autowired
    private MonitoredServiceDao monitoredServiceDao;

    @Autowired
    private SessionUtils sessionUtils;

    @Override
    public OnmsServiceSilence silence(int nodeId, String serviceName, long durationMs, String createdBy) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        return sessionUtils.withTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.getPrimaryService(nodeId, serviceName);
            if (monSvc == null) {
                throw new IllegalArgumentException(
                        String.format("No monitored service '%s' found on node %d", serviceName, nodeId));
            }

            // Cancel any existing active silence first
            cancelInternal(monSvc.getId());

            final Date now = new Date();
            final Date endTime = new Date(now.getTime() + durationMs);
            final OnmsServiceSilence silence = new OnmsServiceSilence(monSvc, now, endTime, createdBy);
            serviceSilenceDao.save(silence);

            LOG.info("Created silence for service {} on node {} until {} (created by {})",
                    serviceName, nodeId, endTime, createdBy);
            return silence;
        });
    }

    @Override
    public void cancel(int nodeId, String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        sessionUtils.withTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.getPrimaryService(nodeId, serviceName);
            if (monSvc == null) {
                return null;
            }
            cancelInternal(monSvc.getId());
            LOG.info("Cancelled silence for service {} on node {}", serviceName, nodeId);
            return null;
        });
    }

    @Override
    public OnmsServiceSilence getActiveSilence(int nodeId, String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        return sessionUtils.withReadOnlyTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.getPrimaryService(nodeId, serviceName);
            if (monSvc == null) {
                return null;
            }
            return serviceSilenceDao.findActiveByMonitoredService(monSvc.getId(), new Date());
        });
    }

    @Override
    public boolean isSilenced(String nodeId, String ipAddr, String serviceName) {
        if (nodeId == null || ipAddr == null || serviceName == null) {
            return false;
        }
        try {
            final int nid = Integer.parseInt(nodeId);
            return sessionUtils.withReadOnlyTransaction(() -> {
                final List<OnmsServiceSilence> silences =
                        serviceSilenceDao.findActiveByNodeAndService(nid, ipAddr, serviceName, new Date());
                return !silences.isEmpty();
            });
        } catch (NumberFormatException e) {
            LOG.debug("Invalid nodeId '{}', not checking silence", nodeId);
            return false;
        }
    }

    private void cancelInternal(Integer ifServiceId) {
        final OnmsServiceSilence active = serviceSilenceDao.findActiveByMonitoredService(ifServiceId, new Date());
        if (active != null) {
            active.setEndTime(new Date());
            serviceSilenceDao.saveOrUpdate(active);
        }
    }
}
