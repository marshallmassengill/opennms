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
package org.opennms.protocols.xml.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;
import org.opennms.core.mate.api.TokenProvider;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionException;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.support.builder.CollectionSetBuilder;
import org.opennms.netmgt.collection.support.builder.Resource;
import org.opennms.protocols.xml.config.Header;
import org.opennms.protocols.xml.config.Request;
import org.opennms.protocols.xml.config.XmlDataCollection;
import org.opennms.protocols.xml.config.XmlSource;

/**
 * Unit-tests {@link AbstractXmlCollectionHandler#refreshAuthTokensInRequest}
 * via an in-memory {@link TokenProvider} and a stub handler subclass that
 * skips the abstract HTTP-bound methods.
 */
public class RefreshAuthTokensTest {

    /**
     * In-memory provider with a fixed cache of (auth-name -> token-value)
     * and a script of "next token to return on getToken" per name.
     */
    private static class InMemoryTokenProvider implements TokenProvider {
        final Map<String, String> currentByName = new HashMap<>();
        final Map<String, String> nextByName = new HashMap<>();

        InMemoryTokenProvider primeCache(String authName, String currentValue) {
            currentByName.put(authName, currentValue);
            return this;
        }

        InMemoryTokenProvider scriptNext(String authName, String nextValue) {
            nextByName.put(authName, nextValue);
            return this;
        }

        @Override
        public Optional<String> getToken(final String authName) {
            return Optional.ofNullable(currentByName.get(authName));
        }

        @Override
        public Optional<String> invalidateByTokenValue(final String tokenValue) {
            for (final Map.Entry<String, String> entry : currentByName.entrySet()) {
                if (entry.getValue().equals(tokenValue)) {
                    currentByName.remove(entry.getKey());
                    if (nextByName.containsKey(entry.getKey())) {
                        currentByName.put(entry.getKey(), nextByName.remove(entry.getKey()));
                    }
                    return Optional.of(entry.getKey());
                }
            }
            return Optional.empty();
        }
    }

    /** Concrete-but-inert handler. */
    private static class StubHandler extends AbstractXmlCollectionHandler {
        @Override
        public CollectionSet collect(CollectionAgent agent, XmlDataCollection collection,
                                     Map<String, Object> parameters) throws CollectionException {
            return null;
        }

        @Override
        protected void fillCollectionSet(String urlString, Request request,
                                         CollectionAgent agent, CollectionSetBuilder builder,
                                         XmlSource source) throws Exception {
        }

        @Override
        protected void processXmlResource(CollectionSetBuilder builder, Resource collectionResource,
                                          String resourceTypeName, String group) {
        }
    }

    @Test
    public void refreshesMatchingHeader() {
        final InMemoryTokenProvider provider = new InMemoryTokenProvider()
                .primeCache("catalyst", "stale-token")
                .scriptNext("catalyst", "fresh-token");

        final Request request = new Request();
        request.setHeaders(Arrays.asList(
                new Header("Content-Type", "application/json"),
                new Header("X-Auth-Token", "stale-token")));

        final StubHandler handler = new StubHandler();
        handler.setTokenProviderForTest(provider);

        final boolean refreshed = handler.refreshAuthTokensInRequest(request);

        assertTrue(refreshed);
        assertEquals("fresh-token", request.getHeader("X-Auth-Token"));
        // Other headers untouched
        assertEquals("application/json", request.getHeader("Content-Type"));
    }

    @Test
    public void noMatchingHeaderReturnsFalse() {
        final InMemoryTokenProvider provider = new InMemoryTokenProvider()
                .primeCache("catalyst", "some-token");

        final Request request = new Request();
        request.setHeaders(Arrays.asList(
                new Header("Authorization", "Bearer not-a-cached-token")));

        final StubHandler handler = new StubHandler();
        handler.setTokenProviderForTest(provider);

        assertFalse(handler.refreshAuthTokensInRequest(request));
        // Header untouched
        assertEquals("Bearer not-a-cached-token", request.getHeader("Authorization"));
    }

    @Test
    public void noTokenProviderReturnsFalse() {
        final Request request = new Request();
        request.setHeaders(Arrays.asList(new Header("X-Auth-Token", "anything")));
        final StubHandler handler = new StubHandler();
        // Don't inject a provider; lookup will fail under unit-test classpath
        // because there's no Spring context for BeanUtils to consult.
        assertFalse(handler.refreshAuthTokensInRequest(request));
    }

    @Test
    public void nullOrEmptyHeadersReturnsFalse() {
        final StubHandler handler = new StubHandler();
        handler.setTokenProviderForTest(new InMemoryTokenProvider().primeCache("a", "tok"));

        assertFalse(handler.refreshAuthTokensInRequest(null));

        final Request emptyRequest = new Request();
        assertFalse(handler.refreshAuthTokensInRequest(emptyRequest));
    }

    @Test
    public void freshTokenLookupFailureLeavesHeaderInPlace() {
        // Cache entry exists but no scripted next-value -- after invalidate,
        // getToken returns empty, so we should not overwrite the header.
        final InMemoryTokenProvider provider = new InMemoryTokenProvider()
                .primeCache("catalyst", "stale-token");
        // No scriptNext call; getToken("catalyst") will return empty after invalidate.

        final Request request = new Request();
        request.setHeaders(Arrays.asList(new Header("X-Auth-Token", "stale-token")));

        final StubHandler handler = new StubHandler();
        handler.setTokenProviderForTest(provider);

        final boolean refreshed = handler.refreshAuthTokensInRequest(request);
        assertFalse(refreshed);
        // Header unchanged because we couldn't get a fresh value
        assertEquals("stale-token", request.getHeader("X-Auth-Token"));
    }
}
