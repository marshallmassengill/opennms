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
package org.opennms.features.distributed.kvstore.json.postgres;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Test;
import org.opennms.features.distributed.kvstore.api.JsonStore;
import org.opennms.features.distributed.kvstore.json.inmemory.InMemoryJsonStore;

public class JsonStoreFactoryTest {

    @After
    public void tearDown() {
        System.clearProperty("opennms.readonly");
    }

    @Test
    public void returnsPostgresStoreByDefault() {
        DataSource ds = mock(DataSource.class);
        JsonStore store = JsonStoreFactory.create(ds);
        assertThat(store, instanceOf(PostgresJsonStore.class));
    }

    @Test
    public void returnsInMemoryStoreWhenReadOnly() {
        System.setProperty("opennms.readonly", "true");
        DataSource ds = mock(DataSource.class);
        JsonStore store = JsonStoreFactory.create(ds);
        assertThat(store, instanceOf(InMemoryJsonStore.class));
    }

    @Test
    public void returnsPostgresStoreWhenReadOnlyIsFalse() {
        System.setProperty("opennms.readonly", "false");
        DataSource ds = mock(DataSource.class);
        JsonStore store = JsonStoreFactory.create(ds);
        assertThat(store, instanceOf(PostgresJsonStore.class));
    }
}
