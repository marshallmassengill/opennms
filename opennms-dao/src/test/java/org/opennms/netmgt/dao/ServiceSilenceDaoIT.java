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
package org.opennms.netmgt.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.opennms.core.utils.InetAddressUtils.addr;

import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.spring.BeanUtils;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.ServiceSilenceDao;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsServiceSilence;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-mockConfigManager.xml",
        "classpath:/META-INF/opennms/applicationContext-databasePopulator.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-mockSnmpPeerFactory.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml"
})
@JUnitConfigurationEnvironment
@JUnitTemporaryDatabase
public class ServiceSilenceDaoIT implements InitializingBean {

    @Autowired
    private ServiceSilenceDao m_serviceSilenceDao;

    @Autowired
    private MonitoredServiceDao m_monitoredServiceDao;

    @Autowired
    private DatabasePopulator m_databasePopulator;

    @Override
    public void afterPropertiesSet() throws Exception {
        BeanUtils.assertAutowiring(this);
    }

    @Before
    public void setUp() {
        m_databasePopulator.populateDatabase();
    }

    @Test
    @Transactional
    public void testSaveAndRetrieve() {
        final OnmsMonitoredService svc = m_monitoredServiceDao.get(
                m_databasePopulator.getNode1().getId(), addr("192.168.1.1"), "SNMP");
        assertNotNull(svc);

        final Date now = new Date();
        final Date endTime = new Date(now.getTime() + 1800000); // 30 minutes
        final OnmsServiceSilence silence = new OnmsServiceSilence(svc, now, endTime, "admin");
        m_serviceSilenceDao.save(silence);
        m_serviceSilenceDao.flush();

        assertNotNull(silence.getId());

        final OnmsServiceSilence retrieved = m_serviceSilenceDao.get(silence.getId());
        assertNotNull(retrieved);
        assertEquals(svc.getId(), retrieved.getMonitoredService().getId());
        assertEquals("admin", retrieved.getCreatedBy());
        assertTrue(retrieved.isActive(now));
    }

    @Test
    @Transactional
    public void testFindActiveByMonitoredService() {
        final OnmsMonitoredService svc = m_monitoredServiceDao.get(
                m_databasePopulator.getNode1().getId(), addr("192.168.1.1"), "SNMP");

        final Date now = new Date();
        final Date endTime = new Date(now.getTime() + 1800000);
        final OnmsServiceSilence silence = new OnmsServiceSilence(svc, now, endTime, "admin");
        m_serviceSilenceDao.save(silence);
        m_serviceSilenceDao.flush();

        // Active silence should be found
        final OnmsServiceSilence found = m_serviceSilenceDao.findActiveByMonitoredService(svc.getId(), now);
        assertNotNull(found);
        assertEquals(silence.getId(), found.getId());

        // After end time, no active silence
        final OnmsServiceSilence expired = m_serviceSilenceDao.findActiveByMonitoredService(
                svc.getId(), new Date(endTime.getTime() + 1000));
        assertNull(expired);
    }

    @Test
    @Transactional
    public void testFindActiveByNodeAndService() {
        final OnmsMonitoredService svc = m_monitoredServiceDao.get(
                m_databasePopulator.getNode1().getId(), addr("192.168.1.1"), "SNMP");
        final int nodeId = m_databasePopulator.getNode1().getId();

        final Date now = new Date();
        final Date endTime = new Date(now.getTime() + 1800000);
        final OnmsServiceSilence silence = new OnmsServiceSilence(svc, now, endTime, "admin");
        m_serviceSilenceDao.save(silence);
        m_serviceSilenceDao.flush();

        // Should find by node + IP + service name
        final List<OnmsServiceSilence> found = m_serviceSilenceDao.findActiveByNodeAndService(
                nodeId, "192.168.1.1", "SNMP", now);
        assertEquals(1, found.size());
        assertEquals(silence.getId(), found.get(0).getId());

        // Different service name should return empty
        final List<OnmsServiceSilence> notFound = m_serviceSilenceDao.findActiveByNodeAndService(
                nodeId, "192.168.1.1", "HTTP", now);
        assertTrue(notFound.isEmpty());

        // After expiry should return empty
        final List<OnmsServiceSilence> expired = m_serviceSilenceDao.findActiveByNodeAndService(
                nodeId, "192.168.1.1", "SNMP", new Date(endTime.getTime() + 1000));
        assertTrue(expired.isEmpty());
    }

    @Test
    @Transactional
    public void testNoActiveSilence() {
        final OnmsMonitoredService svc = m_monitoredServiceDao.get(
                m_databasePopulator.getNode1().getId(), addr("192.168.1.1"), "SNMP");

        // No silences exist
        final OnmsServiceSilence found = m_serviceSilenceDao.findActiveByMonitoredService(svc.getId(), new Date());
        assertNull(found);
    }

    @Test
    @Transactional
    public void testExpiredSilenceNotActive() {
        final OnmsMonitoredService svc = m_monitoredServiceDao.get(
                m_databasePopulator.getNode1().getId(), addr("192.168.1.1"), "SNMP");

        // Create an already-expired silence
        final Date past = new Date(System.currentTimeMillis() - 7200000); // 2 hours ago
        final Date pastEnd = new Date(System.currentTimeMillis() - 3600000); // 1 hour ago
        final OnmsServiceSilence silence = new OnmsServiceSilence(svc, past, pastEnd, "admin");
        m_serviceSilenceDao.save(silence);
        m_serviceSilenceDao.flush();

        // Should not be found as active
        final OnmsServiceSilence found = m_serviceSilenceDao.findActiveByMonitoredService(svc.getId(), new Date());
        assertNull(found);
    }
}
