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
package org.opennms.netmgt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Test;
import org.opennms.netmgt.config.auth.Auth;
import org.opennms.netmgt.config.auth.AuthConfiguration;
import org.opennms.netmgt.config.auth.BasicAuth;
import org.opennms.netmgt.config.auth.TokenFrom;

public class AuthConfigFactoryTest {

    @After
    public void tearDown() {
        AuthConfigFactory.clearForTest();
    }

    private static String validXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<auth-configuration xmlns=\"http://xmlns.opennms.org/xsd/config/auth\">"
                + "  <auth name=\"catalyst-prod\">"
                + "    <url>https://example.com/auth/token</url>"
                + "    <method>POST</method>"
                + "    <basic-auth username=\"u\" password=\"p\"/>"
                + "    <token-from jsonpath=\"Token\"/>"
                + "    <ttl-seconds>3300</ttl-seconds>"
                + "  </auth>"
                + "</auth-configuration>";
    }

    @Test
    public void parsesValidConfig() {
        final AuthConfigFactory factory = new AuthConfigFactory(
                new ByteArrayInputStream(validXml().getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, factory.getAuths().size());
        final Auth auth = factory.getAuth("catalyst-prod").orElseThrow();
        assertEquals("https://example.com/auth/token", auth.getUrl());
        assertEquals("Token", auth.getTokenFrom().getJsonpath());
        assertEquals(Long.valueOf(3300L), auth.getTtlSeconds());
    }

    @Test
    public void getAuthReturnsEmptyForUnknownName() {
        final AuthConfigFactory factory = new AuthConfigFactory(
                new ByteArrayInputStream(validXml().getBytes(StandardCharsets.UTF_8)));
        assertFalse(factory.getAuth("nonexistent").isPresent());
    }

    @Test
    public void singletonInitAndGet() {
        AuthConfigFactory.setInstance(new AuthConfigFactory(
                new ByteArrayInputStream(validXml().getBytes(StandardCharsets.UTF_8))));
        assertTrue(AuthConfigFactory.getInstance().getAuth("catalyst-prod").isPresent());
    }

    @Test
    public void getInstanceBeforeInitFails() {
        // Sanity: not set up
        assertThrows(IllegalStateException.class, AuthConfigFactory::getInstance);
    }

    @Test
    public void rejectsAuthWithoutUrl() {
        final AuthConfiguration cfg = new AuthConfiguration();
        final Auth auth = new Auth();
        auth.setName("bad");
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath("Token");
        auth.setTokenFrom(tf);
        cfg.setAuths(java.util.List.of(auth));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AuthConfigFactory(cfg));
        assertTrue(ex.getMessage().contains("<url>"));
    }

    @Test
    public void rejectsAuthWithoutTokenFrom() {
        final AuthConfiguration cfg = new AuthConfiguration();
        final Auth auth = new Auth();
        auth.setName("bad");
        auth.setUrl("https://example.com/x");
        cfg.setAuths(java.util.List.of(auth));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AuthConfigFactory(cfg));
        assertTrue(ex.getMessage().contains("<token-from>"));
    }

    @Test
    public void rejectsTokenFromWithMultipleStrategies() {
        final AuthConfiguration cfg = new AuthConfiguration();
        final Auth auth = new Auth();
        auth.setName("bad");
        auth.setUrl("https://example.com/x");
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath("Token");
        tf.setHeader("X-Token");  // both set -- forbidden
        auth.setTokenFrom(tf);
        cfg.setAuths(java.util.List.of(auth));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AuthConfigFactory(cfg));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    public void rejectsTokenFromWithNoStrategy() {
        final AuthConfiguration cfg = new AuthConfiguration();
        final Auth auth = new Auth();
        auth.setName("bad");
        auth.setUrl("https://example.com/x");
        auth.setTokenFrom(new TokenFrom());  // empty
        cfg.setAuths(java.util.List.of(auth));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AuthConfigFactory(cfg));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    public void rejectsBasicAuthMissingPassword() {
        final AuthConfiguration cfg = new AuthConfiguration();
        final Auth auth = new Auth();
        auth.setName("bad");
        auth.setUrl("https://example.com/x");
        final BasicAuth ba = new BasicAuth();
        ba.setUsername("u");
        // password is null
        auth.setBasicAuth(ba);
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath("Token");
        auth.setTokenFrom(tf);
        cfg.setAuths(java.util.List.of(auth));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AuthConfigFactory(cfg));
        assertTrue(ex.getMessage().contains("basic-auth"));
    }
}
