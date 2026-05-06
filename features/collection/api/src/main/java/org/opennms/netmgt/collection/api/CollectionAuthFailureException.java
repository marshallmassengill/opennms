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
package org.opennms.netmgt.collection.api;

import java.util.Collections;
import java.util.List;

/**
 * Signals that a collection's downstream endpoint refused the request
 * with an HTTP 401 or 403 response. Used to carry auth-failure context
 * across the collection RPC boundary so the controller can invalidate
 * the cached dynamic-auth token, fetch a fresh one, and re-issue the
 * request.
 *
 * <p>The {@code attemptedHeaderValues} list, when populated, holds the
 * header values that were on the failed request. The controller-side
 * retry path runs each through
 * {@code TokenProvider.invalidateByTokenValue(...)} -- the same
 * primitive the in-process retry path uses for core-local
 * collections.</p>
 */
public class CollectionAuthFailureException extends CollectionException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final List<String> attemptedHeaderValues;

    public CollectionAuthFailureException(final String message, final int statusCode,
                                          final List<String> attemptedHeaderValues) {
        super(message);
        this.statusCode = statusCode;
        this.attemptedHeaderValues = attemptedHeaderValues == null
                ? Collections.emptyList()
                : List.copyOf(attemptedHeaderValues);
    }

    public CollectionAuthFailureException(final String message, final int statusCode,
                                          final List<String> attemptedHeaderValues,
                                          final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.attemptedHeaderValues = attemptedHeaderValues == null
                ? Collections.emptyList()
                : List.copyOf(attemptedHeaderValues);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public List<String> getAttemptedHeaderValues() {
        return attemptedHeaderValues;
    }
}
