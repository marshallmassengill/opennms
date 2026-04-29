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
package org.opennms.protocols.http;

import java.io.IOException;

/**
 * Thrown by {@link HttpUrlConnection} when a downstream HTTP call returns
 * 401 Unauthorized or 403 Forbidden. Subclasses {@link IOException} so
 * callers that don't care about auth-specific handling can treat it like
 * any other I/O failure; callers that do (the XML collector's
 * dynamic-auth retry path) catch this type specifically and attempt to
 * invalidate-and-refresh the offending token.
 */
public class AuthFailureException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public AuthFailureException(final String message, final int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
