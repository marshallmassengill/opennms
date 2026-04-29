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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.config.auth.Auth;
import org.opennms.netmgt.config.auth.AuthConfiguration;
import org.opennms.netmgt.config.auth.TokenFrom;

/**
 * Singleton holding the parsed {@code auth-configuration.xml}.
 * Modeled on {@link VacuumdConfigFactory}.
 *
 * <p>Performs strict load-time validation: each {@code <auth>} block must
 * have a name, a URL, a {@code <token-from>}, and that {@code <token-from>}
 * must specify exactly one of {@code jsonpath}, {@code header}, or
 * {@code body-as-token=true}. Misconfigurations fail fast at startup
 * rather than producing confusing 401s on the first cache miss.</p>
 */
public final class AuthConfigFactory {

    private static AuthConfigFactory m_singleton;

    private final AuthConfiguration m_config;

    public AuthConfigFactory(final InputStream stream) {
        this(JaxbUtils.unmarshal(AuthConfiguration.class, new InputStreamReader(stream)));
    }

    /**
     * Test-friendly constructor that takes an already-unmarshalled config.
     */
    public AuthConfigFactory(final AuthConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        validate(config);
        m_config = config;
    }

    /**
     * Loads {@code etc/auth-configuration.xml} and installs the singleton.
     * Calling this method again is a no-op; use {@link #reload()} to pick
     * up changes on disk.
     */
    public static synchronized void init() throws IOException {
        if (m_singleton != null) {
            return;
        }
        try (InputStream is = new FileInputStream(
                ConfigFileConstants.getFile(ConfigFileConstants.AUTH_CONFIG_FILE_NAME))) {
            setInstance(new AuthConfigFactory(is));
        }
    }

    /**
     * Reloads the config from disk. Replaces the singleton instance on
     * success. The caller is responsible for invalidating any token cache
     * that depends on the previous configuration.
     */
    public static synchronized void reload() throws IOException {
        m_singleton = null;
        init();
    }

    public static synchronized AuthConfigFactory getInstance() {
        if (m_singleton == null) {
            throw new IllegalStateException("AuthConfigFactory.init() has not been called");
        }
        return m_singleton;
    }

    public static synchronized void setInstance(final AuthConfigFactory factory) {
        m_singleton = factory;
    }

    /**
     * Returns the {@link Auth} definition with the given name, if any.
     */
    public synchronized Optional<Auth> getAuth(final String name) {
        return m_config.getAuth(name);
    }

    public synchronized List<Auth> getAuths() {
        return m_config.getAuths();
    }

    public synchronized AuthConfiguration getConfig() {
        return m_config;
    }

    /**
     * Validates the entire configuration. Throws
     * {@link IllegalArgumentException} on the first problem found.
     */
    static void validate(final AuthConfiguration config) {
        final Set<String> seen = new HashSet<>();
        for (final Auth auth : config.getAuths()) {
            validate(auth);
            if (!seen.add(auth.getName())) {
                throw new IllegalArgumentException(
                        "duplicate auth definition name: '" + auth.getName() + "'");
            }
        }
    }

    static void validate(final Auth auth) {
        if (auth.getName() == null || auth.getName().isEmpty()) {
            throw new IllegalArgumentException("auth definition is missing a name attribute");
        }
        if (auth.getUrl() == null || auth.getUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName() + "' has no <url>");
        }
        if (auth.getTokenFrom() == null) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName() + "' has no <token-from>");
        }
        validateTokenFrom(auth);
        if (auth.getBasicAuth() != null) {
            final org.opennms.netmgt.config.auth.BasicAuth ba = auth.getBasicAuth();
            if (ba.getUsername() == null || ba.getPassword() == null) {
                throw new IllegalArgumentException(
                        "auth definition '" + auth.getName()
                                + "' has <basic-auth> without both username and password");
            }
        }
        if (auth.getTtlSeconds() != null && auth.getTtlSeconds() < 0) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName()
                            + "' has a negative <ttl-seconds>; use a positive value or omit the element");
        }
    }

    private static void validateTokenFrom(final Auth auth) {
        final TokenFrom tf = auth.getTokenFrom();
        int specified = 0;
        if (tf.getJsonpath() != null && !tf.getJsonpath().isEmpty()) {
            specified++;
        }
        if (tf.getHeader() != null && !tf.getHeader().isEmpty()) {
            specified++;
        }
        if (tf.isBodyAsToken()) {
            specified++;
        }
        if (specified != 1) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName()
                            + "' must specify exactly one of jsonpath, header, or body-as-token=\"true\""
                            + " on its <token-from>; got " + specified);
        }
    }

    /** Drops the singleton, primarily for tests. */
    static synchronized void clearForTest() {
        m_singleton = null;
    }
}
