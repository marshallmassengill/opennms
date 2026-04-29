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

import java.util.Objects;

import org.apache.commons.lang.StringUtils;
import org.opennms.netmgt.config.AuthConfigFactory;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventIpcManagerFactory;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.events.api.model.IParm;
import org.opennms.netmgt.model.events.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Reacts to a {@code reloadDaemonConfig} event with
 * {@code daemonName=AuthConfig} by re-reading
 * {@code etc/auth-configuration.xml}. Cached tokens are flushed as part
 * of the reload so the next request through the metadata DSL drives a
 * fresh acquisition against the (possibly updated) auth definition.
 *
 * <p>To trigger:
 * <pre>
 *   uei.opennms.org/internal/reloadDaemonConfig
 *   parm: daemonName=AuthConfig
 * </pre>
 *
 * <p>Self-registration: this bean lives in {@code daoContext}, which is
 * built before {@code eventDaemonContext}. The OpenNMS
 * {@link EventIpcManager} therefore is not yet wired when this bean is
 * constructed. Spring publishes a {@link ContextRefreshedEvent} on every
 * context refresh, including child contexts, and those events propagate
 * up to listeners in the parent. We use that signal to self-register
 * with the {@link EventIpcManagerFactory} once it is initialised; the
 * registration is one-shot, guarded by a flag.</p>
 */
public class AuthConfigReloadListener
        implements EventListener, ApplicationListener<ContextRefreshedEvent> {

    /** Name advertised for daemon-reload routing. */
    public static final String NAME = "AuthConfig";

    private static final Logger LOG = LoggerFactory.getLogger(AuthConfigReloadListener.class);

    private final TokenCache tokenCache;
    private volatile EventIpcManager eventIpcManager;
    private volatile boolean registered;

    public AuthConfigReloadListener(final TokenCache tokenCache) {
        this.tokenCache = Objects.requireNonNull(tokenCache, "tokenCache");
    }

    /**
     * Test-only constructor that wires the manager up front and skips
     * the lazy self-registration dance. Production code should rely on
     * {@link #onApplicationEvent} instead.
     */
    AuthConfigReloadListener(final TokenCache tokenCache, final EventIpcManager eventIpcManager) {
        this.tokenCache = Objects.requireNonNull(tokenCache, "tokenCache");
        this.eventIpcManager = Objects.requireNonNull(eventIpcManager, "eventIpcManager");
    }

    @Override
    public void onApplicationEvent(final ContextRefreshedEvent event) {
        if (registered) {
            return;
        }
        final EventIpcManager mgr;
        try {
            mgr = EventIpcManagerFactory.getIpcManager();
        } catch (final IllegalStateException notReady) {
            // eventDaemonContext has not finished building yet; we will
            // try again on the next ContextRefreshedEvent that propagates
            // up to us.
            return;
        }
        this.eventIpcManager = mgr;
        mgr.addEventListener(this, EventConstants.RELOAD_DAEMON_CONFIG_UEI);
        registered = true;
        LOG.info("Registered as listener for {} (daemonName={})",
                EventConstants.RELOAD_DAEMON_CONFIG_UEI, NAME);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void onEvent(final IEvent e) {
        if (e == null || !EventConstants.RELOAD_DAEMON_CONFIG_UEI.equals(e.getUei())) {
            return;
        }
        final IParm daemonNameParm = e.getParm(EventConstants.PARM_DAEMON_NAME);
        if (daemonNameParm == null || daemonNameParm.getValue() == null) {
            return;
        }
        if (!NAME.equalsIgnoreCase(daemonNameParm.getValue().getContent())) {
            return;
        }
        LOG.info("Reloading auth-configuration.xml");
        try {
            AuthConfigFactory.reload();
            tokenCache.invalidateAll();
            LOG.info("auth-configuration.xml reload successful; cache flushed");
            sendEventQuietly(new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, NAME)
                    .addParam(EventConstants.PARM_DAEMON_NAME, NAME)
                    .getEvent());
        } catch (final Exception t) {
            LOG.error("auth-configuration.xml reload failed", t);
            sendEventQuietly(new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, NAME)
                    .addParam(EventConstants.PARM_DAEMON_NAME, NAME)
                    .addParam(EventConstants.PARM_REASON, StringUtils.abbreviate(t.getLocalizedMessage(), 128))
                    .getEvent());
        }
    }

    private void sendEventQuietly(final org.opennms.netmgt.xml.event.Event event) {
        if (eventIpcManager == null) {
            return;
        }
        try {
            eventIpcManager.sendNow(event);
        } catch (final Exception t) {
            LOG.warn("Failed to publish {}", event.getUei(), t);
        }
    }
}
