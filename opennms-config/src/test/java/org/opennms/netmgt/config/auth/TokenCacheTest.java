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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for {@link TokenCache}. {@link TokenAcquirer} is exercised
 * through subclassed test doubles rather than mocks so the cache's
 * interaction with it stays explicit.
 */
public class TokenCacheTest {

    /** A test acquirer that records call count and returns scripted tokens. */
    private static class CountingAcquirer extends TokenAcquirer {
        final AtomicInteger calls = new AtomicInteger();
        final String tokenValue;
        final Instant expiresAt;

        CountingAcquirer(final String tokenValue, final Instant expiresAt) {
            this.tokenValue = tokenValue;
            this.expiresAt = expiresAt;
        }

        @Override
        public CachedToken acquire(final Auth auth) {
            calls.incrementAndGet();
            return new CachedToken(tokenValue + "-" + calls.get(), expiresAt);
        }
    }

    private static Auth namedAuth(final String name) {
        final Auth a = new Auth();
        a.setName(name);
        a.setUrl("http://does-not-matter");
        return a;
    }

    @Test
    public void cachesAcquiredToken() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        assertEquals("tok-1", cache.getToken(namedAuth("a")));
        assertEquals("tok-1", cache.getToken(namedAuth("a")));
        assertEquals(1, acquirer.calls.get());
    }

    @Test
    public void differentAuthsDoNotShareCacheEntries() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        cache.getToken(namedAuth("a"));
        cache.getToken(namedAuth("b"));
        assertEquals(2, acquirer.calls.get());
    }

    @Test
    public void invalidateForcesReacquisition() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        assertEquals("tok-1", cache.getToken(namedAuth("a")));
        cache.invalidate("a");
        assertEquals("tok-2", cache.getToken(namedAuth("a")));
    }

    @Test
    public void expiredEntryTriggersReacquisition() throws IOException {
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        final MutableClock clock = new MutableClock(t0);
        final CountingAcquirer acquirer = new CountingAcquirer("tok",
                t0.plusSeconds(60));
        final TokenCache cache = new TokenCache(acquirer, clock);

        cache.getToken(namedAuth("a"));
        assertTrue(cache.isCached("a"));

        clock.advanceSeconds(120); // past expiry
        assertFalse(cache.isCached("a"));

        cache.getToken(namedAuth("a"));
        assertEquals(2, acquirer.calls.get());
    }

    @Test
    public void acquisitionFailureSurfaces() {
        final TokenAcquirer failing = new TokenAcquirer() {
            @Override
            public CachedToken acquire(final Auth auth) throws IOException {
                throw new IOException("boom");
            }
        };
        final TokenCache cache = new TokenCache(failing);

        final IOException ex = assertThrows(IOException.class,
                () -> cache.getToken(namedAuth("a")));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    public void invalidateByTokenValueRemovesAndReturnsName() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        final String value = cache.getToken(namedAuth("a"));
        assertTrue(cache.isCached("a"));

        final java.util.Optional<String> evicted = cache.invalidateByTokenValue(value);
        assertTrue(evicted.isPresent());
        assertEquals("a", evicted.get());
        assertFalse(cache.isCached("a"));
    }

    @Test
    public void invalidateByTokenValueWithUnknownValueReturnsEmpty() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        cache.getToken(namedAuth("a"));
        assertFalse(cache.invalidateByTokenValue("a-different-token").isPresent());
        assertTrue(cache.isCached("a"));
    }

    @Test
    public void invalidateByTokenValueOnlyHitsTheMatchingEntry() throws IOException {
        // Two distinct auth names cached; only the matching one is removed.
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);

        final String aValue = cache.getToken(namedAuth("a"));
        cache.getToken(namedAuth("b"));

        final java.util.Optional<String> evicted = cache.invalidateByTokenValue(aValue);
        assertEquals("a", evicted.orElse(null));
        assertFalse(cache.isCached("a"));
        assertTrue(cache.isCached("b"));
    }

    @Test
    public void invalidateByTokenValueWithNullOrEmptyReturnsEmpty() throws IOException {
        final CountingAcquirer acquirer = new CountingAcquirer("tok", null);
        final TokenCache cache = new TokenCache(acquirer);
        cache.getToken(namedAuth("a"));
        assertFalse(cache.invalidateByTokenValue(null).isPresent());
        assertFalse(cache.invalidateByTokenValue("").isPresent());
        assertTrue(cache.isCached("a"));
    }

    @Test
    public void acquisitionFailureDoesNotCachePoison() {
        final AtomicInteger calls = new AtomicInteger();
        final TokenAcquirer flaky = new TokenAcquirer() {
            @Override
            public CachedToken acquire(final Auth auth) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    throw new IOException("transient");
                }
                return new CachedToken("ok", null);
            }
        };
        final TokenCache cache = new TokenCache(flaky);

        assertThrows(IOException.class, () -> cache.getToken(namedAuth("a")));
        // Second call should retry the acquirer (not return a cached failure).
        try {
            assertEquals("ok", cache.getToken(namedAuth("a")));
        } catch (final IOException e) {
            throw new AssertionError("second call should have succeeded", e);
        }
    }

    /** A clock whose instant can be advanced manually. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void advanceSeconds(final long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }
    }
}
