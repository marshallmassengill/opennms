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
package org.opennms.netmgt.dao.hibernate;

import java.util.Date;
import java.util.List;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.dao.api.ServiceSilenceDao;
import org.opennms.netmgt.model.OnmsServiceSilence;

public class ServiceSilenceDaoHibernate extends AbstractDaoHibernate<OnmsServiceSilence, Integer>
        implements ServiceSilenceDao {

    public ServiceSilenceDaoHibernate() {
        super(OnmsServiceSilence.class);
    }

    @Override
    public List<OnmsServiceSilence> findActiveByNodeAndService(int nodeId, String ipAddr, String serviceName, Date now) {
        return find("from OnmsServiceSilence s " +
                    "where s.monitoredService.ipInterface.node.id = ? " +
                    "and s.monitoredService.ipInterface.ipAddress = ? " +
                    "and s.monitoredService.serviceType.name = ? " +
                    "and s.startTime <= ? and s.endTime > ?",
                nodeId, InetAddressUtils.addr(ipAddr), serviceName, now, now);
    }

    @Override
    public OnmsServiceSilence findActiveByMonitoredService(Integer ifServiceId, Date now) {
        return findUnique("from OnmsServiceSilence s " +
                          "where s.monitoredService.id = ? " +
                          "and s.startTime <= ? and s.endTime > ?",
                ifServiceId, now, now);
    }
}
