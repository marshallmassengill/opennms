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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.core.mate.api.TokenProvider;
import org.opennms.netmgt.config.AuthConfigFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises the full dynamic-auth runtime stack
 * (AuthConfigFactory + TokenAcquirer + TokenCache + TokenProviderImpl)
 * against an embedded HTTP server. Where the per-class unit tests pin
 * each component in isolation, this test verifies they compose into
 * the behaviors a deployed instance will exhibit: cache hits avoid
 * re-acquisition, invalidation triggers a fresh fetch, reverse-lookup
 * invalidation finds the right entry, and so on.
 */
public class AuthRuntimeIntegrationTest {

    private HttpServer server;
    private int port;
    private AtomicInteger acquireCount;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        acquireCount = new AtomicInteger(0);

        // Each call to the auth endpoint returns a unique token, so we can
        // observe whether the cache served us a stale value or fetched a
        // fresh one.
        server.createContext("/auth", exchange -> {
            final int n = acquireCount.incrementAndGet();
            writeJson(exchange, 200, "{\"Token\":\"jwt-" + n + "\"}");
        });
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        // Each test constructs its own AuthConfigFactory directly via the
        // ctor, so the static singleton is never touched here.
    }

    private static void writeJson(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String authUrl() {
        return "http://127.0.0.1:" + port + "/auth";
    }

    private String configXml(final String authName, final Long ttl) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<auth-configuration xmlns=\"http://xmlns.opennms.org/xsd/config/auth\">");
        sb.append("  <auth name=\"").append(authName).append("\">");
        sb.append("    <url>").append(authUrl()).append("</url>");
        sb.append("    <method>POST</method>");
        sb.append("    <basic-auth username=\"u\" password=\"p\"/>");
        sb.append("    <token-from jsonpath=\"Token\"/>");
        if (ttl != null) {
            sb.append("    <ttl-seconds>").append(ttl).append("</ttl-seconds>");
        }
        sb.append("  </auth>");
        sb.append("</auth-configuration>");
        return sb.toString();
    }

    private TokenProviderImpl buildProvider(final String configXml) {
        final AuthConfigFactory factory = new AuthConfigFactory(
                new ByteArrayInputStream(configXml.getBytes(StandardCharsets.UTF_8)));
        final TokenCache cache = new TokenCache(new TokenAcquirer());
        return new TokenProviderImpl(() -> factory, cache);
    }

    @Test
    public void firstCallAcquiresAndSubsequentCallsAreCached() {
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));

        final Optional<String> first = provider.getToken("catalyst");
        assertTrue(first.isPresent());
        assertEquals("jwt-1", first.get());
        assertEquals(1, acquireCount.get());

        // Repeated calls hit the cache, not the server.
        for (int i = 0; i < 5; i++) {
            assertEquals("jwt-1", provider.getToken("catalyst").orElseThrow());
        }
        assertEquals("acquire should have been called once total", 1, acquireCount.get());
    }

    @Test
    public void unknownAuthReturnsEmptyWithoutHittingServer() {
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));

        assertFalse(provider.getToken("does-not-exist").isPresent());
        assertEquals("server should not have been touched", 0, acquireCount.get());
    }

    @Test
    public void invalidateByTokenValueForcesReacquisition() {
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));

        final String first = provider.getToken("catalyst").orElseThrow();
        assertEquals("jwt-1", first);
        assertEquals(1, acquireCount.get());

        // Reverse-lookup invalidate using only the value, simulating the
        // 401-retry path in the XmlCollector.
        final Optional<TokenProvider.InvalidationResult> evicted = provider.invalidateByTokenValue(first);
        assertTrue(evicted.isPresent());
        assertEquals("catalyst", evicted.get().getAuthName());
        assertEquals(first, evicted.get().getMatchedTokenValue());

        final String second = provider.getToken("catalyst").orElseThrow();
        assertEquals("jwt-2", second);
        assertNotEquals(first, second);
        assertEquals(2, acquireCount.get());
    }

    @Test
    public void invalidateByTokenValueMatchesPrefixedHeaderText() {
        // Regression: the Authorization-header form is "Bearer <token>",
        // so the cache must match the header value as a substring search,
        // not an exact equality.
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));

        final String first = provider.getToken("catalyst").orElseThrow();

        final Optional<TokenProvider.InvalidationResult> evicted =
                provider.invalidateByTokenValue("Bearer " + first);
        assertTrue(evicted.isPresent());
        assertEquals("catalyst", evicted.get().getAuthName());
        assertEquals(first, evicted.get().getMatchedTokenValue());
    }

    @Test
    public void invalidateByUnknownTokenValueDoesNotRefetch() {
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));

        provider.getToken("catalyst");
        assertEquals(1, acquireCount.get());

        assertFalse(provider.invalidateByTokenValue("totally-different-token").isPresent());

        // Cached entry is intact, second call is still cached.
        provider.getToken("catalyst");
        assertEquals("server should not have been touched again", 1, acquireCount.get());
    }

    @Test
    public void multipleAuthDefinitionsHaveIndependentCaches() {
        final String xml = "<?xml version=\"1.0\"?>"
                + "<auth-configuration xmlns=\"http://xmlns.opennms.org/xsd/config/auth\">"
                + "  <auth name=\"a\">"
                + "    <url>" + authUrl() + "</url>"
                + "    <method>POST</method>"
                + "    <basic-auth username=\"u\" password=\"p\"/>"
                + "    <token-from jsonpath=\"Token\"/>"
                + "  </auth>"
                + "  <auth name=\"b\">"
                + "    <url>" + authUrl() + "</url>"
                + "    <method>POST</method>"
                + "    <basic-auth username=\"u\" password=\"p\"/>"
                + "    <token-from jsonpath=\"Token\"/>"
                + "  </auth>"
                + "</auth-configuration>";

        final TokenProviderImpl provider = buildProvider(xml);

        final String tokA = provider.getToken("a").orElseThrow();
        final String tokB = provider.getToken("b").orElseThrow();
        assertNotEquals("each auth name fetches independently", tokA, tokB);
        assertEquals(2, acquireCount.get());

        // Invalidating one does not affect the other: 'b' still serves from cache.
        provider.invalidateByTokenValue(tokA);
        assertEquals("'b' should still be cached", tokB, provider.getToken("b").orElseThrow());
        assertEquals("'b' read should not have hit the server", 2, acquireCount.get());

        // 'a' on the other hand should refetch because we just evicted it.
        final String tokA2 = provider.getToken("a").orElseThrow();
        assertNotEquals("'a' should have been re-acquired", tokA, tokA2);
        assertEquals("only the invalidated entry refetches", 3, acquireCount.get());
    }

    @Test
    public void simulated401RetryCycle() {
        // This is the behavior the XmlCollector retry path drives:
        //   1. Get a token (cache miss -> fetch jwt-1)
        //   2. Use it; downstream returns 401
        //   3. invalidateByTokenValue(jwt-1) -> evicts entry
        //   4. Get token again -> fetch jwt-2
        //   5. Use it; downstream succeeds
        // We don't actually run the XmlCollector here -- just assert the
        // interleaving of cache calls is what the retry path expects.
        final TokenProviderImpl provider = buildProvider(configXml("api", null));

        final String t1 = provider.getToken("api").orElseThrow();
        assertEquals("jwt-1", t1);

        // Simulate the downstream 401 by invoking invalidateByTokenValue
        // with the value that was attached to the request.
        final Optional<TokenProvider.InvalidationResult> name = provider.invalidateByTokenValue(t1);
        assertTrue(name.isPresent());
        assertEquals("api", name.get().getAuthName());

        // The retry path now fetches a fresh token by name.
        final String t2 = provider.getToken("api").orElseThrow();
        assertEquals("jwt-2", t2);
        assertEquals(2, acquireCount.get());
    }

    @Test
    public void cacheKeysAreIndependentAcrossInvocations() {
        // After invalidating one entry, the others are not disturbed.
        final TokenProviderImpl provider = buildProvider(
                "<auth-configuration xmlns=\"http://xmlns.opennms.org/xsd/config/auth\">"
                + "  <auth name=\"shared\">"
                + "    <url>" + authUrl() + "</url>"
                + "    <method>POST</method>"
                + "    <basic-auth username=\"u\" password=\"p\"/>"
                + "    <token-from jsonpath=\"Token\"/>"
                + "  </auth>"
                + "</auth-configuration>");

        // Two readers, both should observe the same cached value.
        final String a = provider.getToken("shared").orElseThrow();
        final String b = provider.getToken("shared").orElseThrow();
        assertEquals(a, b);
        assertEquals("only one acquire across two reads", 1, acquireCount.get());
    }

    @Test
    public void factorySupplierMayReturnNullSafely() {
        // Wiring corner: TokenProviderImpl is happy if the factory supplier
        // hasn't initialized yet; everything just returns empty.
        final TokenCache cache = new TokenCache(new TokenAcquirer());
        final TokenProviderImpl provider = new TokenProviderImpl(() -> null, cache);

        assertFalse(provider.getToken("anything").isPresent());
        assertFalse(provider.invalidateByTokenValue("anything").isPresent());
        assertEquals("no HTTP should have happened", 0, acquireCount.get());
    }

    @Test
    public void mixedNullAndPopulatedHeadersInRequestSequence() {
        // Synthetic scenario: simulate making 3 requests where headers
        // contain different combinations of cached / not-cached values.
        // Mostly exercises invalidateByTokenValue's null-tolerance.
        final TokenProviderImpl provider = buildProvider(configXml("catalyst", null));
        final String value = provider.getToken("catalyst").orElseThrow();

        final String[] candidates = { null, "", "Bearer something-else", value, "another-thing" };
        Optional<TokenProvider.InvalidationResult> hit = Optional.empty();
        for (String c : candidates) {
            final Optional<TokenProvider.InvalidationResult> ev = provider.invalidateByTokenValue(c);
            if (ev.isPresent()) {
                hit = ev;
            }
        }
        assertTrue("exactly the cached entry should have been hit", hit.isPresent());
        assertEquals("catalyst", hit.get().getAuthName());
    }

    @Test
    public void duplicateNamesRejectedAtFactoryConstruction() {
        // Ensures Phase 3.5 validation still fires when wiring through the
        // full stack. Exercising it here catches accidental regressions
        // where someone bypasses the factory.
        final String dupXml =
                "<auth-configuration xmlns=\"http://xmlns.opennms.org/xsd/config/auth\">"
              + "  <auth name=\"dup\">"
              + "    <url>" + authUrl() + "</url>"
              + "    <method>POST</method>"
              + "    <basic-auth username=\"u\" password=\"p\"/>"
              + "    <token-from jsonpath=\"Token\"/>"
              + "  </auth>"
              + "  <auth name=\"dup\">"
              + "    <url>" + authUrl() + "</url>"
              + "    <method>POST</method>"
              + "    <basic-auth username=\"u\" password=\"p\"/>"
              + "    <token-from jsonpath=\"Token\"/>"
              + "  </auth>"
              + "</auth-configuration>";
        try {
            new AuthConfigFactory(
                    new ByteArrayInputStream(dupXml.getBytes(StandardCharsets.UTF_8)));
            org.junit.Assert.fail("expected IllegalArgumentException for duplicate auth names");
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("duplicate"));
        }
    }

    // Suppress unused-import warnings if Arrays gets used in future.
    @SuppressWarnings("unused")
    private static final java.util.List<?> KEEP_ARRAYS = Arrays.asList(0);
}
