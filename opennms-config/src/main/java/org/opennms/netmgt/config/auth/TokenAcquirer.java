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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.opennms.core.web.HttpClientWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Performs the HTTP call described by an {@link Auth} definition and
 * returns the resulting {@link CachedToken}. Used by {@link TokenCache} on
 * cache misses and on token invalidation.
 *
 * <p>This class is intentionally stateless. A single instance can be shared
 * across many auth definitions and many concurrent callers. Each
 * {@link #acquire(Auth)} call constructs a fresh
 * {@link HttpClientWrapper} configured for the specific auth definition's
 * SSL/proxy/timeout settings, executes the request, and closes the
 * wrapper.</p>
 */
public class TokenAcquirer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;

    private final int connectTimeoutMs;
    private final int socketTimeoutMs;

    public TokenAcquirer() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS);
    }

    public TokenAcquirer(final int connectTimeoutMs, final int socketTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    /**
     * Executes the auth call described by {@code auth} and returns the
     * resulting cached token.
     *
     * @throws IOException on network errors, non-2xx status, or token
     *                     extraction failure
     */
    public CachedToken acquire(final Auth auth) throws IOException {
        if (auth.getUrl() == null || auth.getUrl().isEmpty()) {
            throw new IOException("auth definition '" + auth.getName() + "' has no <url>");
        }
        if (auth.getTokenFrom() == null) {
            throw new IOException("auth definition '" + auth.getName() + "' has no <token-from>");
        }

        final HttpRequestBase request = buildRequest(auth);
        try (HttpClientWrapper client = configureClient(auth);
             CloseableHttpResponse response = client.execute(request)) {
            final int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("auth call to " + auth.getUrl()
                        + " returned status " + status);
            }
            final String token = extractToken(auth.getTokenFrom(), response);
            return new CachedToken(token, computeExpiresAt(auth));
        }
    }

    /**
     * Builds an {@link HttpClientWrapper} configured per the auth
     * definition's SSL/proxy/timeout settings.
     */
    private HttpClientWrapper configureClient(final Auth auth) throws IOException {
        final HttpClientWrapper wrapper = HttpClientWrapper.create()
                .setConnectionTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs);
        if (auth.isUseSystemProxy()) {
            wrapper.useSystemProxySettings();
        }
        if (auth.isDisableSslVerification()) {
            try {
                wrapper.useRelaxedSSL("https");
            } catch (final GeneralSecurityException e) {
                throw new IOException(
                        "failed to configure relaxed SSL for auth '" + auth.getName() + "'", e);
            }
        }
        return wrapper;
    }

    private HttpRequestBase buildRequest(final Auth auth) throws IOException {
        final String method = auth.getMethod();
        final HttpRequestBase request;
        if ("GET".equalsIgnoreCase(method)) {
            request = new HttpGet(auth.getUrl());
        } else if ("POST".equalsIgnoreCase(method)) {
            request = new HttpPost(auth.getUrl());
        } else if ("PUT".equalsIgnoreCase(method)) {
            request = new HttpPut(auth.getUrl());
        } else {
            throw new IOException("unsupported HTTP method '" + method
                    + "' in auth definition '" + auth.getName() + "'");
        }

        if (auth.getBasicAuth() != null) {
            final String userPass = auth.getBasicAuth().getUsername() + ":"
                    + auth.getBasicAuth().getPassword();
            final String encoded = Base64.getEncoder()
                    .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
            request.setHeader("Authorization", "Basic " + encoded);
        }

        for (final org.opennms.netmgt.config.auth.Header h : auth.getHeaders()) {
            request.setHeader(h.getName(), h.getValue());
        }

        if (auth.getContent() != null && request instanceof HttpEntityEnclosingRequestBase) {
            final org.opennms.netmgt.config.auth.Content c = auth.getContent();
            final StringEntity entity = new StringEntity(
                    c.getData() == null ? "" : c.getData(),
                    StandardCharsets.UTF_8);
            if (c.getType() != null && !c.getType().isEmpty()) {
                entity.setContentType(c.getType());
            }
            ((HttpEntityEnclosingRequestBase) request).setEntity(entity);
        }

        return request;
    }

    private String extractToken(final TokenFrom tf,
                                final HttpResponse response) throws IOException {
        // Header extraction does not consume the body; check it first.
        if (tf.getHeader() != null && !tf.getHeader().isEmpty()) {
            final Header h = response.getFirstHeader(tf.getHeader());
            if (h == null) {
                throw new IOException("auth response missing header: " + tf.getHeader());
            }
            return h.getValue();
        }

        final HttpEntity entity = response.getEntity();
        final String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);

        if (tf.isBodyAsToken()) {
            String trimmed = body.trim();
            // Some APIs (notably vSphere v8) return a JSON-encoded bare string
            // as the body. Strip surrounding double quotes when present.
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                throw new IOException("auth response body was empty");
            }
            return trimmed;
        }

        if (tf.getJsonpath() != null && !tf.getJsonpath().isEmpty()) {
            return extractJsonPath(body, tf.getJsonpath());
        }

        throw new IOException("token-from must specify jsonpath, header, or body-as-token=\"true\"");
    }

    /**
     * Walks a slash-separated path through a JSON document. For example,
     * {@code token/token} returns the {@code token} field of the {@code token}
     * field of the root object.
     */
    private String extractJsonPath(final String body, final String path) throws IOException {
        JsonNode node = JSON.readTree(body);
        for (final String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = node.path(segment);
        }
        if (node.isMissingNode() || node.isNull()) {
            throw new IOException("jsonpath '" + path + "' did not resolve in auth response");
        }
        return node.asText();
    }

    private Instant computeExpiresAt(final Auth auth) {
        final Long ttl = auth.getTtlSeconds();
        if (ttl == null || ttl <= 0) {
            return null;
        }
        return Instant.now().plusSeconds(ttl);
    }
}
