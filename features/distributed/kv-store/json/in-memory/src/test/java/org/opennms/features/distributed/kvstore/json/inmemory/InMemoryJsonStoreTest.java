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
package org.opennms.features.distributed.kvstore.json.inmemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.Before;
import org.junit.Test;

public class InMemoryJsonStoreTest {

    private InMemoryJsonStore store;

    @Before
    public void setUp() {
        store = new InMemoryJsonStore();
    }

    @Test
    public void putAndGet() {
        store.put("key1", "{\"value\":1}", "ctx");
        Optional<String> result = store.get("key1", "ctx");
        assertTrue(result.isPresent());
        assertEquals("{\"value\":1}", result.get());
    }

    @Test
    public void getReturnsEmptyForMissingKey() {
        Optional<String> result = store.get("missing", "ctx");
        assertFalse(result.isPresent());
    }

    @Test
    public void getReturnsEmptyForWrongContext() {
        store.put("key1", "value", "ctx-a");
        Optional<String> result = store.get("key1", "ctx-b");
        assertFalse(result.isPresent());
    }

    @Test
    public void putOverwritesExistingValue() {
        store.put("key1", "first", "ctx");
        store.put("key1", "second", "ctx");
        assertEquals("second", store.get("key1", "ctx").get());
    }

    @Test
    public void deleteRemovesEntry() {
        store.put("key1", "value", "ctx");
        store.delete("key1", "ctx");
        assertFalse(store.get("key1", "ctx").isPresent());
    }

    @Test
    public void deleteNonexistentKeyDoesNotThrow() {
        store.delete("missing", "ctx");
    }

    @Test
    public void enumerateContextReturnsOnlyMatchingContext() {
        store.put("k1", "v1", "ctx-a");
        store.put("k2", "v2", "ctx-a");
        store.put("k3", "v3", "ctx-b");

        Map<String, String> results = store.enumerateContext("ctx-a");
        assertEquals(2, results.size());
        assertEquals("v1", results.get("k1"));
        assertEquals("v2", results.get("k2"));
    }

    @Test
    public void enumerateContextReturnsEmptyForUnknownContext() {
        store.put("k1", "v1", "ctx-a");
        Map<String, String> results = store.enumerateContext("ctx-b");
        assertTrue(results.isEmpty());
    }

    @Test
    public void getLastUpdatedReturnsTimestamp() {
        store.put("key1", "value", "ctx");
        OptionalLong lastUpdated = store.getLastUpdated("key1", "ctx");
        assertTrue(lastUpdated.isPresent());
        assertTrue(lastUpdated.getAsLong() > 0);
    }

    @Test
    public void getLastUpdatedReturnsEmptyForMissingKey() {
        OptionalLong lastUpdated = store.getLastUpdated("missing", "ctx");
        assertFalse(lastUpdated.isPresent());
    }

    @Test
    public void getLastUpdatedAdvancesOnOverwrite() throws Exception {
        store.put("key1", "first", "ctx");
        long ts1 = store.getLastUpdated("key1", "ctx").getAsLong();
        Thread.sleep(5);
        store.put("key1", "second", "ctx");
        long ts2 = store.getLastUpdated("key1", "ctx").getAsLong();
        assertTrue(ts2 > ts1);
    }

    @Test
    public void getIfStaleReturnsEmptyWhenKeyMissing() {
        Optional<Optional<String>> result = store.getIfStale("missing", "ctx", 0);
        assertFalse(result.isPresent());
    }

    @Test
    public void getIfStaleReturnsValueWhenCallerIsStale() {
        store.put("key1", "value", "ctx");
        long ts = store.getLastUpdated("key1", "ctx").getAsLong();
        // Caller has an older timestamp — data is newer, so return it
        Optional<Optional<String>> result = store.getIfStale("key1", "ctx", ts - 1);
        assertTrue(result.isPresent());
        assertTrue(result.get().isPresent());
        assertEquals("value", result.get().get());
    }

    @Test
    public void getIfStaleReturnsEmptyOptionalWhenCallerIsCurrent() {
        store.put("key1", "value", "ctx");
        long ts = store.getLastUpdated("key1", "ctx").getAsLong();
        // Caller has the same timestamp — not stale, return empty value
        Optional<Optional<String>> result = store.getIfStale("key1", "ctx", ts);
        assertTrue(result.isPresent());
        assertFalse(result.get().isPresent());
    }

    @Test
    public void getNameReturnsInMemory() {
        assertEquals("In-Memory", store.getName());
    }
}
