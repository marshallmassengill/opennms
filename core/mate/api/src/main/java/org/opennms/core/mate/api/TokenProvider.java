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

import java.util.Optional;

/**
 * Resolves named auth definitions to a current token value. Used by
 * {@link AuthScope} to back the {@code ${auth:<name>}} placeholder in the
 * metadata DSL. Implementations are responsible for fetching, caching,
 * and refreshing tokens behind this interface.
 *
 * <p>The interface is in the {@code core.mate.api} module so that
 * {@link AuthScope} (and indirectly the metadata DSL) can reach it without
 * pulling in the auth-runtime implementation. The actual implementation
 * lives in {@code opennms-config}.</p>
 */
public interface TokenProvider {

    /**
     * Returns the current token for the named auth definition, or empty
     * if no definition is registered under that name.
     *
     * <p>Implementations may throw {@link RuntimeException} (typically
     * wrapping an {@link java.io.IOException}) if a definition exists but
     * acquisition fails. Callers should treat that as a hard failure --
     * surfacing the misconfiguration is preferable to silently producing
     * an empty token that will produce a confusing 401 downstream.</p>
     */
    Optional<String> getToken(String authName);
}
