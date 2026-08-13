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
package org.opennms.netmgt.syslogd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opennms.netmgt.daemon.AbstractServiceDaemon;
import org.opennms.netmgt.daemon.DaemonTools;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.annotations.EventHandler;
import org.opennms.netmgt.events.api.annotations.EventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * The received messages are converted into XML and sent to eventd.
 * </p>
 * <p>
 * <strong>Note: </strong>Syslogd is a PausableFiber so as to receive control
 * events. However, a 'pause' on Syslogd has no impact on the receiving and
 * processing of syslog messages.
 * </p>
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 * @author <a href="mailto:david@opennms.org">David Hustace</a>
 * @author <a href="mailto:dj@opennms.org">DJ Gregor</a>
 * @author <a href="mailto:joed@opennms.org">Johan Edstrom</a>
 * @author <a href="mailto:mhuot@opennms.org">Mike Huot</a>
 */
@EventListener(name=Syslogd.LOG4J_CATEGORY, logPrefix=Syslogd.LOG4J_CATEGORY)
public class Syslogd extends AbstractServiceDaemon {

    private static final Logger LOG = LoggerFactory.getLogger(Syslogd.class);

    /**
     * The name of the logging category for Syslogd.
     */
    public static final String LOG4J_CATEGORY = "syslogd";

    /**
     * One per transport. A receiver whose transport is not configured is still held
     * here and simply does nothing when started, which keeps the wiring free of
     * conditional bean construction.
     */
    private List<SyslogReceiver> m_receivers = new ArrayList<>();

    private final List<Thread> m_threads = new ArrayList<>();

    /**
     * <p>Constructor for Syslogd.</p>
     */
    public Syslogd() {
        super(LOG4J_CATEGORY);
    }

    public List<SyslogReceiver> getSyslogReceivers() {
        return Collections.unmodifiableList(m_receivers);
    }

    public void setSyslogReceivers(final List<SyslogReceiver> receivers) {
        m_receivers = receivers == null ? new ArrayList<>() : new ArrayList<>(receivers);
    }

    /**
     * Convenience for the single-transport case.
     */
    public SyslogReceiver getSyslogReceiver() {
        return m_receivers.isEmpty() ? null : m_receivers.get(0);
    }

    /**
     * Convenience for the single-transport case, replacing whatever was set before.
     */
    public void setSyslogReceiver(final SyslogReceiver receiver) {
        m_receivers = new ArrayList<>();
        if (receiver != null) {
            m_receivers.add(receiver);
        }
    }

    /**
     * <p>onInit</p>
     */
    @Override
    protected void onInit() {
        // Nothing to do
    }

    /**
     * <p>onStart</p>
     */
    @Override
    protected void onStart() {
        LOG.debug("Starting SyslogHandler");

        for (final SyslogReceiver receiver : m_receivers) {
            final Thread rThread = new Thread(receiver, receiver.getName());
            m_threads.add(rThread);
            try {
                rThread.start();
            } catch (RuntimeException e) {
                rThread.interrupt();
                throw e;
            }
        }
    }

    /**
     * <p>onStop</p>
     */
    @Override
    protected void onStop() {
        for (final SyslogReceiver receiver : m_receivers) {
            LOG.debug("stop: Stopping the Syslogd receiver {}", receiver.getName());
            try {
                receiver.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("stop: Interrupted while stopping the Syslog receiver {}: {}", receiver.getName(), e.getMessage());
            } catch (Throwable e) {
                // Carry on so that one receiver failing to stop does not leave the
                // others running with nothing to shut them down.
                LOG.error("stop: Failed to stop the Syslog receiver {}", receiver.getName(), e);
            }
            LOG.debug("stop: Stopped the Syslogd receiver {}", receiver.getName());
        }
        m_threads.clear();
    }

    private void handleConfigurationChanged() {
        stop();
        for (final SyslogReceiver receiver : m_receivers) {
            try {
                receiver.reload();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        start();
    }

    @EventHandler(uei = EventConstants.RELOAD_DAEMON_CONFIG_UEI)
    public void handleReloadEvent(IEvent e) {
        DaemonTools.handleReloadEvent(e, Syslogd.LOG4J_CATEGORY, (event) -> handleConfigurationChanged());
    }
}
