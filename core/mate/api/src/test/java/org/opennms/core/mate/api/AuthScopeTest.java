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
package org.opennms.core.mate.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

public class AuthScopeTest {

    /** A simple in-memory TokenProvider for tests. */
    private static class MapTokenProvider implements TokenProvider {
        final Map<String, String> tokens = new HashMap<>();

        @Override
        public Optional<String> getToken(final String authName) {
            return Optional.ofNullable(tokens.get(authName));
        }
    }

    @Test
    public void resolvesKnownAuthName() {
        final MapTokenProvider provider = new MapTokenProvider();
        provider.tokens.put("catalyst-prod", "jwt-abc");
        final AuthScope scope = new AuthScope(provider);

        final Optional<Scope.ScopeValue> result = scope.get(new ContextKey("auth", "catalyst-prod"));

        assertTrue(result.isPresent());
        assertEquals("jwt-abc", result.get().value);
        assertEquals(Scope.ScopeName.GLOBAL, result.get().scopeName);
    }

    @Test
    public void unknownAuthNameYieldsEmpty() {
        final AuthScope scope = new AuthScope(new MapTokenProvider());
        assertFalse(scope.get(new ContextKey("auth", "missing")).isPresent());
    }

    @Test
    public void otherContextNamespacesAreNotOurs() {
        final MapTokenProvider provider = new MapTokenProvider();
        provider.tokens.put("anything", "would-be-token");
        final AuthScope scope = new AuthScope(provider);

        // An scv-namespaced lookup with the same key shouldn't match the auth scope.
        assertFalse(scope.get(new ContextKey("scv", "anything")).isPresent());
        assertFalse(scope.get(new ContextKey("node", "anything")).isPresent());
    }

    @Test
    public void providerExceptionPropagates() {
        final TokenProvider failing = name -> {
            throw new RuntimeException("acquisition failed for " + name);
        };
        final AuthScope scope = new AuthScope(failing);

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> scope.get(new ContextKey("auth", "broken")));
        assertTrue(ex.getMessage().contains("broken"));
    }

    @Test
    public void keysIsEmpty() {
        // Auth definitions are dynamic; we don't enumerate them here.
        assertTrue(new AuthScope(new MapTokenProvider()).keys().isEmpty());
    }
}
