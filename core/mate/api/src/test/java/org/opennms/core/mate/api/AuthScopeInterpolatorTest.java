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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

/**
 * Verifies that {@code ${auth:<name>}} placeholders flow through the
 * standard {@link Interpolator} pipeline correctly when an
 * {@link AuthScope} is part of the scope chain. Where {@link AuthScopeTest}
 * exercises the scope's {@code get} method directly, this test exercises
 * it through the metadata DSL the way real callers will.
 */
public class AuthScopeInterpolatorTest {

    private static class InMemoryTokenProvider implements TokenProvider {
        final Map<String, String> tokens = new HashMap<>();

        @Override
        public Optional<String> getToken(final String authName) {
            return Optional.ofNullable(tokens.get(authName));
        }
    }

    private static AuthScope authScopeWith(final String name, final String value) {
        final InMemoryTokenProvider provider = new InMemoryTokenProvider();
        provider.tokens.put(name, value);
        return new AuthScope(provider);
    }

    @Test
    public void interpolatorResolvesAuthPlaceholder() {
        final Scope chain = authScopeWith("catalyst-prod", "fresh-token-abc");
        final Interpolator.Result result = Interpolator.interpolate(
                "Bearer ${auth:catalyst-prod}", chain);
        assertEquals("Bearer fresh-token-abc", result.output);
    }

    @Test
    public void multiplePlaceholdersInOneStringResolveIndependently() {
        final InMemoryTokenProvider provider = new InMemoryTokenProvider();
        provider.tokens.put("a", "AAA");
        provider.tokens.put("b", "BBB");
        final Interpolator.Result result = Interpolator.interpolate(
                "first=${auth:a}; second=${auth:b}", new AuthScope(provider));
        assertEquals("first=AAA; second=BBB", result.output);
    }

    @Test
    public void unknownAuthFallsThroughToLiteralDefault() {
        // Standard metadata DSL fallback: ${ns:key|"literal"} resolves to
        // "literal" if the namespace lookup misses.
        final Scope chain = authScopeWith("known", "X");
        final Interpolator.Result result = Interpolator.interpolate(
                "${auth:nonexistent|\"fallback-value\"}", chain);
        assertEquals("fallback-value", result.output);
    }

    @Test
    public void unknownAuthWithoutFallbackBecomesEmpty() {
        // Documented metadata DSL behavior is empty-on-miss; AuthScope must
        // not interfere with that.
        final Scope chain = authScopeWith("known", "X");
        final Interpolator.Result result = Interpolator.interpolate(
                "Bearer ${auth:nonexistent}", chain);
        assertEquals("Bearer ", result.output);
    }

    @Test
    public void authScopeDoesNotMatchOtherNamespaces() {
        final InMemoryTokenProvider provider = new InMemoryTokenProvider();
        provider.tokens.put("anything", "would-be-token");
        // env:HOME (or any other context) should not match the auth scope;
        // since no other scope resolves it either, it falls through to
        // empty per DSL contract.
        final Interpolator.Result result = Interpolator.interpolate(
                "x=${env:DOES_NOT_EXIST_REALLY_12345}", new AuthScope(provider));
        assertEquals("x=", result.output);
    }

    @Test
    public void resultPartsRecordTheAuthHit() {
        // Interpolator records every successful resolution as a ResultPart
        // with the resolved value. Useful for downstream auditing.
        final Scope chain = authScopeWith("catalyst", "tok");
        final Interpolator.Result result = Interpolator.interpolate(
                "Bearer ${auth:catalyst}", chain);
        assertEquals(1, result.parts.size());
        assertEquals("tok", result.parts.get(0).value.value);
        assertEquals(Scope.ScopeName.GLOBAL, result.parts.get(0).value.scopeName);
        assertTrue(result.parts.get(0).input.contains("auth:catalyst"));
    }
}
