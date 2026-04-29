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

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@code ${auth:<name>}} placeholders in the metadata DSL by
 * delegating to a {@link TokenProvider}. Modeled on
 * {@link SecureCredentialsVaultScope}.
 *
 * <p>Returns {@link Optional#empty()} for context keys that are not in the
 * {@code auth} namespace. For an unknown auth name within the namespace,
 * also returns empty -- the metadata DSL will then fall through to any
 * configured default ({@code ${auth:foo|"bar"}}) or, if there is none,
 * leave the substitution empty per the documented DSL behavior.</p>
 */
public class AuthScope implements Scope {

    public static final String CONTEXT = "auth";

    /**
     * Per-thread re-entrancy guard. Set while resolving an
     * {@code ${auth:foo}} placeholder so that a re-entrant resolution
     * (which would happen if an auth definition itself contained
     * {@code ${auth:bar}}) returns empty rather than recursing forever.
     */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final TokenProvider tokenProvider;

    public AuthScope(final TokenProvider tokenProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    @Override
    public Optional<ScopeValue> get(final ContextKey contextKey) {
        if (!CONTEXT.equals(contextKey.context)) {
            return Optional.empty();
        }
        if (RESOLVING.get()) {
            // Re-entrant lookup. Auth definitions cannot reference
            // other auth definitions; treat as unresolved so the
            // metadata DSL produces an empty substitution (or the
            // configured literal default if one was provided via the
            // ${auth:foo|"bar"} form).
            return Optional.empty();
        }
        // Pass the scope of the in-flight interpolation pass down to the
        // provider so the auth definition's own ${node:...} or
        // ${requisition:...} placeholders resolve against the calling
        // context (e.g. the node being collected against).
        final Scope callingScope = Interpolator.currentCallingScope().orElse(null);
        RESOLVING.set(Boolean.TRUE);
        try {
            return tokenProvider.getToken(contextKey.key, callingScope)
                    .map(token -> new ScopeValue(ScopeName.GLOBAL, token));
        } finally {
            // remove() rather than set(FALSE) so we don't leave a
            // permanent map entry on every pool thread that ever
            // resolved an auth.
            RESOLVING.remove();
        }
    }

    @Override
    public Set<ContextKey> keys() {
        // Auth definitions are dynamic from the perspective of the scope --
        // we don't surface a static list of them through this API.
        return Collections.emptySet();
    }
}
