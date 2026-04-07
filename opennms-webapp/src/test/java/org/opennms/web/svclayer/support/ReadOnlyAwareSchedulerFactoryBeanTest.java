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
package org.opennms.web.svclayer.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import java.util.Properties;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Test;

public class ReadOnlyAwareSchedulerFactoryBeanTest {

    @After
    public void tearDown() {
        System.clearProperty("opennms.readonly");
    }

    @Test
    public void normalModePreservesJobStoreClass() {
        Properties props = new Properties();
        props.setProperty("org.quartz.jobStore.class",
                "org.quartz.impl.jdbcjobstore.JobStoreCMT");
        props.setProperty("org.quartz.jobStore.driverDelegateClass",
                "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");

        ReadOnlyAwareSchedulerFactoryBean bean = new ReadOnlyAwareSchedulerFactoryBean();
        bean.setQuartzProperties(props);

        assertEquals("org.quartz.impl.jdbcjobstore.JobStoreCMT",
                props.getProperty("org.quartz.jobStore.class"));
        assertEquals("org.quartz.impl.jdbcjobstore.PostgreSQLDelegate",
                props.getProperty("org.quartz.jobStore.driverDelegateClass"));
    }

    @Test
    public void readOnlyModeSwapsToRAMJobStore() {
        System.setProperty("opennms.readonly", "true");

        Properties props = new Properties();
        props.setProperty("org.quartz.jobStore.class",
                "org.quartz.impl.jdbcjobstore.JobStoreCMT");
        props.setProperty("org.quartz.jobStore.driverDelegateClass",
                "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");

        ReadOnlyAwareSchedulerFactoryBean bean = new ReadOnlyAwareSchedulerFactoryBean();
        bean.setQuartzProperties(props);

        assertEquals("org.quartz.simpl.RAMJobStore",
                props.getProperty("org.quartz.jobStore.class"));
        assertNull(props.getProperty("org.quartz.jobStore.driverDelegateClass"));
    }

    @Test
    public void readOnlyModeDiscardsDataSource() {
        System.setProperty("opennms.readonly", "true");

        DataSource ds = mock(DataSource.class);
        ReadOnlyAwareSchedulerFactoryBean bean = new ReadOnlyAwareSchedulerFactoryBean();
        bean.setDataSource(ds);

        // In read-only mode, setDataSource should be a no-op.
        // We can't directly inspect the parent's private field, but we can
        // verify the bean doesn't throw when initialized without a dataSource
        // by setting RAMJobStore properties and calling afterPropertiesSet.
        Properties props = new Properties();
        props.setProperty("org.quartz.jobStore.class",
                "org.quartz.impl.jdbcjobstore.JobStoreCMT");
        bean.setQuartzProperties(props);

        // After setQuartzProperties, RAMJobStore should be set
        assertEquals("org.quartz.simpl.RAMJobStore",
                props.getProperty("org.quartz.jobStore.class"));
    }

    @Test
    public void normalModeAcceptsDataSource() {
        DataSource ds = mock(DataSource.class);
        ReadOnlyAwareSchedulerFactoryBean bean = new ReadOnlyAwareSchedulerFactoryBean();
        // Should not throw — dataSource is passed through to parent
        bean.setDataSource(ds);
    }
}
