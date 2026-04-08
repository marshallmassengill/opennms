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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.config.PollerConfig;
import org.opennms.netmgt.config.poller.Package;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.poller.AdhocPollException;
import org.opennms.netmgt.poller.AdhocPollResult;
import org.opennms.netmgt.poller.AdhocPollService;
import org.opennms.netmgt.poller.LocationAwarePollerClient;
import org.opennms.netmgt.poller.PollerResponse;
import org.opennms.netmgt.poller.ServiceMonitorLocator;
import org.opennms.netmgt.poller.support.SimpleMonitoredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implementation of {@link AdhocPollService} that resolves polling configuration
 * automatically and executes polls via the existing {@link LocationAwarePollerClient}.
 *
 * <p>Ad-hoc polls are executed <b>without adaptors</b>, meaning they do not
 * persist latency data, update status, or trigger state transitions.</p>
 */
public class AdhocPollServiceImpl implements AdhocPollService {

    private static final Logger LOG = LoggerFactory.getLogger(AdhocPollServiceImpl.class);

    @Autowired
    private LocationAwarePollerClient locationAwarePollerClient;

    @Autowired
    private MonitoredServiceDao monitoredServiceDao;

    @Autowired
    private PollerConfig pollerConfig;

    @Autowired
    private SessionUtils sessionUtils;

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        final ServiceResolution resolution = sessionUtils.withReadOnlyTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.getPrimaryService(nodeId, serviceName);
            if (monSvc == null) {
                throw new AdhocPollException.ServiceNotFound(nodeId, serviceName);
            }
            return resolveConfig(toResolution(monSvc));
        });

        return executePoll(resolution);
    }

    @Override
    public CompletableFuture<AdhocPollResult> poll(int nodeId, InetAddress ipAddress, String serviceName) {
        Objects.requireNonNull(ipAddress, "ipAddress must not be null");
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        final ServiceResolution resolution = sessionUtils.withReadOnlyTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.get(nodeId, ipAddress, serviceName);
            if (monSvc == null) {
                throw new AdhocPollException.ServiceNotFound(nodeId, serviceName);
            }
            return resolveConfig(toResolution(monSvc));
        });

        return executePoll(resolution);
    }

    @Override
    public List<String> findAllMatchingPackages(int nodeId, String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        final String ipAddr = sessionUtils.withReadOnlyTransaction(() -> {
            final OnmsMonitoredService monSvc = monitoredServiceDao.getPrimaryService(nodeId, serviceName);
            if (monSvc == null) {
                throw new AdhocPollException.ServiceNotFound(nodeId, serviceName);
            }
            return InetAddressUtils.str(monSvc.getIpAddress());
        });

        final List<String> matchingPackages = new ArrayList<>();
        for (final Package pkg : pollerConfig.getPackages()) {
            if (pkg.getPerspectiveOnly()) {
                continue;
            }
            if (!pollerConfig.isServiceInPackageAndEnabled(serviceName, pkg)) {
                continue;
            }
            if (!pollerConfig.isInterfaceInPackage(ipAddr, pkg)) {
                continue;
            }
            matchingPackages.add(pkg.getName());
        }
        return Collections.unmodifiableList(matchingPackages);
    }

    private CompletableFuture<AdhocPollResult> executePoll(ServiceResolution resolution) {
        final Date executionTimestamp = new Date();

        LOG.info("Executing ad-hoc poll for node={}, ip={}, service={}, monitor={}, package={}",
                resolution.nodeId, resolution.ipAddress, resolution.serviceName,
                resolution.monitorClassName, resolution.packageName);

        final SimpleMonitoredService svc = new SimpleMonitoredService(
                resolution.ipAddr, resolution.nodeId, resolution.nodeLabel,
                resolution.serviceName, resolution.location);

        // Execute without adaptors — no latency storage, no status updates, no state transitions
        final CompletableFuture<PollerResponse> future = locationAwarePollerClient.poll()
                .withService(svc)
                .withMonitorClassName(resolution.monitorClassName)
                .withAttributes(resolution.parameters)
                .withPatternVariables(resolution.patternVariables)
                .execute();

        return future.thenApply(response -> {
            final AdhocPollResult result = new AdhocPollResult(
                    response.getPollStatus(),
                    resolution.monitorClassName,
                    resolution.packageName,
                    resolution.ipAddress,
                    resolution.nodeId,
                    resolution.serviceName,
                    executionTimestamp);

            LOG.info("Ad-hoc poll completed: {}", result);
            return result;
        });
    }

    private ServiceResolution toResolution(OnmsMonitoredService monSvc) {
        final InetAddress ipAddr = monSvc.getIpAddress();
        final String ipAddress = InetAddressUtils.str(ipAddr);
        final int nodeId = monSvc.getNodeId();
        final String nodeLabel = monSvc.getIpInterface().getNode().getLabel();
        final String location = monSvc.getIpInterface().getNode().getLocation().getLocationName();
        final String serviceName = monSvc.getServiceName();

        return new ServiceResolution(ipAddr, ipAddress, nodeId, nodeLabel, location, serviceName);
    }

    /**
     * Resolves poller configuration (package, monitor, parameters) for a service.
     * The DB lookup populates the basic fields; this method fills in the config fields.
     */
    private ServiceResolution resolveConfig(ServiceResolution base) {
        final Package pkg = pollerConfig.findPackageForService(base.ipAddress, base.serviceName);
        if (pkg == null) {
            throw new AdhocPollException.PackageNotFound(base.ipAddress, base.serviceName);
        }

        final Package.ServiceMatch serviceMatch = pkg.findService(base.serviceName)
                .orElseThrow(() -> new AdhocPollException.PackageNotFound(base.ipAddress, base.serviceName));

        final ServiceMonitorLocator locator = pollerConfig.getServiceMonitorLocator(serviceMatch.service.getName())
                .orElseThrow(() -> new AdhocPollException.MonitorNotFound(base.serviceName));

        final Map<String, Object> parameters = serviceMatch.service.getParameterMap();

        base.packageName = pkg.getName();
        base.monitorClassName = locator.getServiceLocatorKey();
        base.parameters = parameters;
        base.patternVariables = serviceMatch.patternVariables;

        return base;
    }

    /**
     * Internal holder for resolved service information.
     * Populated in two phases: DB lookup, then config resolution.
     */
    private static class ServiceResolution {
        final InetAddress ipAddr;
        final String ipAddress;
        final int nodeId;
        final String nodeLabel;
        final String location;
        final String serviceName;

        // Populated by resolveConfig
        String packageName;
        String monitorClassName;
        Map<String, Object> parameters = Collections.emptyMap();
        Map<String, String> patternVariables = Collections.emptyMap();

        ServiceResolution(InetAddress ipAddr, String ipAddress, int nodeId,
                          String nodeLabel, String location, String serviceName) {
            this.ipAddr = ipAddr;
            this.ipAddress = ipAddress;
            this.nodeId = nodeId;
            this.nodeLabel = nodeLabel;
            this.location = location;
            this.serviceName = serviceName;
        }
    }
}
