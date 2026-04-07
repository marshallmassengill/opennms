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

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * A {@link SchedulerFactoryBean} subclass that switches to an in-memory
 * RAMJobStore when the system is running in read-only mode
 * ({@code opennms.readonly=true}), preventing any database writes to
 * the Quartz tables.
 *
 * When {@code dataSource} is set on {@link SchedulerFactoryBean}, Spring
 * forces the use of a JDBC job store regardless of the
 * {@code org.quartz.jobStore.class} property. This subclass intercepts
 * {@link #setDataSource(DataSource)} to discard the datasource in
 * read-only mode, allowing the RAMJobStore configuration to take effect.
 */
public class ReadOnlyAwareSchedulerFactoryBean extends SchedulerFactoryBean {

    @Override
    public void setDataSource(DataSource dataSource) {
        if (Boolean.getBoolean("opennms.readonly")) {
            // Don't set the dataSource — this prevents Spring from
            // forcing JobStoreCMT and allows RAMJobStore to be used
            return;
        }
        super.setDataSource(dataSource);
    }

    @Override
    public void setQuartzProperties(Properties quartzProperties) {
        if (Boolean.getBoolean("opennms.readonly")) {
            quartzProperties.setProperty("org.quartz.jobStore.class",
                    "org.quartz.simpl.RAMJobStore");
            quartzProperties.remove("org.quartz.jobStore.driverDelegateClass");
        }
        super.setQuartzProperties(quartzProperties);
    }
}
