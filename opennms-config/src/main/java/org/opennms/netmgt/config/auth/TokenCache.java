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
package org.opennms.netmgt.config.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory cache of auth tokens, keyed by auth-definition name.
 *
 * <p>Concurrent calls for the same name serialize on the per-key bin lock
 * inherited from {@link ConcurrentHashMap#compute}; only one underlying
 * acquire will run for a given name even under contention. Calls for
 * different names proceed in parallel.</p>
 *
 * <p>The cache is purely in-memory. Reloading or restarting OpenNMS drops
 * all cached tokens.</p>
 */
public class TokenCache {

    private final TokenAcquirer acquirer;
    private final Clock clock;
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public TokenCache(final TokenAcquirer acquirer) {
        this(acquirer, Clock.systemUTC());
    }

    /**
     * Test-friendly constructor that accepts a {@link Clock} for controlling
     * the perceived current time.
     */
    public TokenCache(final TokenAcquirer acquirer, final Clock clock) {
        this.acquirer = Objects.requireNonNull(acquirer, "acquirer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the cached token for {@code auth.getName()}, acquiring a new
     * one if missing or expired. Concurrent callers for the same name share
     * the result of a single acquire.
     *
     * @throws IOException if acquisition fails
     */
    public String getToken(final Auth auth) throws IOException {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(auth.getName(), "auth.name");

        final Instant now = clock.instant();
        try {
            final CachedToken cached = cache.compute(auth.getName(), (name, existing) -> {
                if (existing != null && !existing.isExpired(now)) {
                    return existing;
                }
                try {
                    return acquirer.acquire(auth);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return cached.getValue();
        } catch (final UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Drops any cached token for {@code authName}. The next {@link #getToken}
     * call for that name will trigger a fresh acquire. Intended to be called
     * when a downstream call returns 401/403.
     */
    public void invalidate(final String authName) {
        if (authName != null) {
            cache.remove(authName);
        }
    }

    /**
     * Reverse-lookup invalidation: scans the cache for an entry whose token
     * value matches {@code tokenValue}, removes it if found, and returns the
     * auth name it belonged to. Used by HTTP retry paths that observe a 401
     * on a request and need to figure out which auth's token to invalidate
     * without having the auth name on hand.
     *
     * <p>Safe to call with a null or empty value (returns empty).</p>
     */
    public Optional<String> invalidateByTokenValue(final String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            return Optional.empty();
        }
        for (final Map.Entry<String, CachedToken> entry : cache.entrySet()) {
            if (tokenValue.equals(entry.getValue().getValue())) {
                cache.remove(entry.getKey(), entry.getValue());
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Drops every cached token. Useful on configuration reload. */
    public void invalidateAll() {
        cache.clear();
    }

    /** Returns true if a cached, non-expired token exists for {@code authName}. */
    public boolean isCached(final String authName) {
        if (authName == null) {
            return false;
        }
        final CachedToken existing = cache.get(authName);
        return existing != null && !existing.isExpired(clock.instant());
    }
}
