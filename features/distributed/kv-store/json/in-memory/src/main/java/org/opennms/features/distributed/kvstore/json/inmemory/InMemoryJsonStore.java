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

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.opennms.features.distributed.kvstore.api.AbstractAsyncKeyValueStore;
import org.opennms.features.distributed.kvstore.api.JsonStore;

/**
 * An in-memory implementation of {@link JsonStore} backed by a {@link ConcurrentHashMap}.
 * Suitable for read-only UI instances where database writes are not permitted.
 * Data is ephemeral and lost on restart.
 */
public class InMemoryJsonStore extends AbstractAsyncKeyValueStore<String> implements JsonStore {
    private final Map<Map.Entry<String, String>, Map.Entry<String, Long>> store = new ConcurrentHashMap<>();

    @Override
    public long put(String key, String value, String context, Integer ttlInSeconds) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Objects.requireNonNull(context);

        long timestamp = System.currentTimeMillis();
        store.put(new AbstractMap.SimpleImmutableEntry<>(key, context),
                new AbstractMap.SimpleImmutableEntry<>(value, timestamp));
        return timestamp;
    }

    @Override
    public Optional<String> get(String key, String context) {
        Map.Entry<String, Long> entry = store.get(new AbstractMap.SimpleImmutableEntry<>(key, context));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.getKey());
    }

    @Override
    public Optional<Optional<String>> getIfStale(String key, String context, long timestamp) {
        OptionalLong lastUpdated = getLastUpdated(key, context);

        if (!lastUpdated.isPresent()) {
            return Optional.empty();
        }

        if (timestamp >= lastUpdated.getAsLong()) {
            return Optional.of(Optional.empty());
        }

        return Optional.of(get(key, context));
    }

    @Override
    public OptionalLong getLastUpdated(String key, String context) {
        Map.Entry<String, Long> entry = store.get(new AbstractMap.SimpleImmutableEntry<>(key, context));
        if (entry == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(entry.getValue());
    }

    @Override
    public String getName() {
        return "In-Memory";
    }

    @Override
    public Map<String, String> enumerateContext(String context) {
        return Collections.unmodifiableMap(store.entrySet()
                .stream()
                .filter(e -> e.getKey().getValue().equals(context))
                .collect(Collectors.toMap(e -> e.getKey().getKey(), e -> e.getValue().getKey())));
    }

    @Override
    public void delete(String key, String context) {
        store.remove(new AbstractMap.SimpleImmutableEntry<>(key, context));
    }
}
