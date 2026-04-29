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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link TokenAcquirer} against an embedded {@link HttpServer}.
 * Each test installs its own handler so the auth-response shape can vary.
 */
public class TokenAcquirerTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String urlFor(final String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @FunctionalInterface
    private interface IOExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void install(final String path, final IOExchangeHandler handler) {
        server.createContext(path, exchange -> {
            lastExchange.set(exchange);
            try {
                handler.handle(exchange);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void writeJson(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Auth basicAuthDefinition(final String url, final String jsonpath) {
        final Auth auth = new Auth();
        auth.setName("test");
        auth.setUrl(url);
        auth.setMethod("POST");
        auth.setBasicAuth(new BasicAuth("u", "p"));
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath(jsonpath);
        auth.setTokenFrom(tf);
        return auth;
    }

    @Test
    public void extractsTokenViaJsonPath_catalystShape() throws IOException {
        install("/dna/auth", exchange ->
                writeJson(exchange, 200, "{\"Token\":\"jwt-abc\"}"));

        final Auth auth = basicAuthDefinition(urlFor("/dna/auth"), "Token");
        final CachedToken token = new TokenAcquirer().acquire(auth);

        assertEquals("jwt-abc", token.getValue());
    }

    @Test
    public void extractsTokenViaJsonPath_nestedF5Shape() throws IOException {
        install("/login", exchange ->
                writeJson(exchange, 200, "{\"token\":{\"token\":\"f5-xyz\",\"timeout\":1200}}"));

        final Auth auth = basicAuthDefinition(urlFor("/login"), "token/token");
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath("token/token");
        auth.setTokenFrom(tf);
        // Switch to JSON-body credentials to mirror F5 more closely.
        auth.setBasicAuth(null);
        auth.setHeaders(Arrays.asList(new Header("Content-Type", "application/json")));
        auth.setContent(new Content("application/json",
                "{\"username\":\"u\",\"password\":\"p\",\"loginProviderName\":\"tmos\"}"));

        final CachedToken token = new TokenAcquirer().acquire(auth);
        assertEquals("f5-xyz", token.getValue());
    }

    @Test
    public void extractsTokenViaResponseHeader() throws IOException {
        install("/keystone", exchange -> {
            exchange.getResponseHeaders().add("X-Subject-Token", "ks-abc");
            try {
                exchange.sendResponseHeaders(201, -1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try { exchange.close(); } catch (Exception ignored) {}
        });

        final Auth auth = new Auth();
        auth.setName("keystone");
        auth.setUrl(urlFor("/keystone"));
        auth.setMethod("POST");
        final TokenFrom tf = new TokenFrom();
        tf.setHeader("X-Subject-Token");
        auth.setTokenFrom(tf);

        final CachedToken token = new TokenAcquirer().acquire(auth);
        assertEquals("ks-abc", token.getValue());
    }

    @Test
    public void bodyAsTokenStripsSurroundingQuotes_vSphereShape() throws IOException {
        install("/api/session", exchange ->
                writeJson(exchange, 200, "\"vmware-session-1234\""));

        final Auth auth = new Auth();
        auth.setName("vsphere");
        auth.setUrl(urlFor("/api/session"));
        auth.setMethod("POST");
        auth.setBasicAuth(new BasicAuth("u", "p"));
        final TokenFrom tf = new TokenFrom();
        tf.setBodyAsToken(Boolean.TRUE);
        auth.setTokenFrom(tf);

        final CachedToken token = new TokenAcquirer().acquire(auth);
        assertEquals("vmware-session-1234", token.getValue());
    }

    @Test
    public void ttlTranslatesToExpiresAt() throws IOException {
        install("/auth", exchange ->
                writeJson(exchange, 200, "{\"Token\":\"x\"}"));

        final Auth auth = basicAuthDefinition(urlFor("/auth"), "Token");
        auth.setTtlSeconds(900L);

        final CachedToken token = new TokenAcquirer().acquire(auth);

        assertNotNull("ttl was set, expiresAt should be present",
                token.getExpiresAt().orElse(null));
        // Roughly: should be a future instant within ~900 seconds.
        final long secondsAhead = token.getExpiresAt().get().getEpochSecond()
                - java.time.Instant.now().getEpochSecond();
        assertTrue("expiresAt should be in the future and roughly ttl seconds ahead",
                secondsAhead > 0 && secondsAhead <= 900);
    }

    @Test
    public void omittingTtlYieldsNoExpiry() throws IOException {
        install("/auth", exchange ->
                writeJson(exchange, 200, "{\"Token\":\"x\"}"));

        final Auth auth = basicAuthDefinition(urlFor("/auth"), "Token");
        final CachedToken token = new TokenAcquirer().acquire(auth);

        assertTrue("with no ttl, expiresAt should be empty",
                token.getExpiresAt().isEmpty());
    }

    @Test
    public void non2xxResponseFails() {
        install("/auth", exchange -> {
            try {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        final Auth auth = basicAuthDefinition(urlFor("/auth"), "Token");
        final IOException ex = assertThrows(IOException.class,
                () -> new TokenAcquirer().acquire(auth));
        assertTrue("error message should mention status",
                ex.getMessage().contains("401"));
    }

    @Test
    public void missingExpectedJsonPathFails() {
        install("/auth", exchange ->
                writeJson(exchange, 200, "{\"different\":\"value\"}"));

        final Auth auth = basicAuthDefinition(urlFor("/auth"), "Token");
        final IOException ex = assertThrows(IOException.class,
                () -> new TokenAcquirer().acquire(auth));
        assertTrue(ex.getMessage().contains("jsonpath"));
    }

    @Test
    public void basicAuthHeaderIsSent() throws IOException {
        final AtomicReference<String> seen = new AtomicReference<>();
        install("/auth", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { writeJson(exchange, 200, "{\"Token\":\"x\"}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        final Auth auth = basicAuthDefinition(urlFor("/auth"), "Token");
        new TokenAcquirer().acquire(auth);

        assertNotNull("Authorization header should have been sent", seen.get());
        assertTrue("should be basic", seen.get().startsWith("Basic "));
    }
}
