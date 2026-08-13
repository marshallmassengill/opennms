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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Dictionary;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.util.KeyValueHolder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.ipc.sink.api.MessageDispatcherFactory;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.camel.CamelBlueprintTest;
import org.opennms.distributed.core.api.Identity;
import org.opennms.distributed.core.api.MinionIdentity;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.springframework.test.context.ContextConfiguration;

/**
 * Proves the Minion blueprint resolves and actually binds.
 *
 * This is what catches an OSGi import that the compiler cannot see and a property whose
 * string value the container will not convert, both of which otherwise only show up on a
 * real Minion.
 */
@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:/META-INF/opennms/emptyContext.xml" })
public class SyslogdReceiverNettyTcpBlueprintIT extends CamelBlueprintTest {

    private static int s_port;

    @SuppressWarnings("rawtypes")
    @Override
    protected void addServicesOnStartup(Map<String, KeyValueHolder<Object, Dictionary>> services) {
        final MessageDispatcherFactory messageProducerFactory = mock(MessageDispatcherFactory.class);
        final MinionIdentity minionIdentity = mock(MinionIdentity.class);
        final DistPollerDao distPollerDao = mock(DistPollerDao.class);
        services.put(MessageDispatcherFactory.class.getName(),
                new KeyValueHolder<Object, Dictionary>(messageProducerFactory, new Properties()));
        services.put(MinionIdentity.class.getName(),
                new KeyValueHolder<Object, Dictionary>(minionIdentity, new Properties()));
        services.put(Identity.class.getName(), new KeyValueHolder<>(minionIdentity, new Properties()));
        services.put(DistPollerDao.class.getName(), new KeyValueHolder<>(distPollerDao, new Properties()));
    }

    /**
     * Binds an ephemeral port rather than the shipped default, so that a developer
     * already running something on 1601 does not see this fail.
     */
    @Override
    protected String useOverridePropertiesWithConfigAdmin(final Dictionary props) throws Exception {
        s_port = findFreePort();
        final Dictionary<String, String> overrides = (Dictionary<String, String>) props;
        overrides.put("syslog.tcp.listen.port", Integer.toString(s_port));
        overrides.put("syslog.tcp.listen.interface", "127.0.0.1");
        overrides.put("syslog.tcp.framing", "octet-counting");
        overrides.put("syslog.tcp.max.message.size", "32768");
        overrides.put("syslog.tcp.max.connections", "16");
        overrides.put("syslog.tcp.idle.timeout", "120");
        return "org.opennms.netmgt.syslog";
    }

    @Override
    protected String getBlueprintDescriptor() {
        return "blueprint-syslog-listener-netty-tcp.xml,blueprint-empty-camel-context.xml";
    }

    @Test
    public void registersTheReceiverAndBindsTheConfiguredPort() throws Exception {
        final SyslogReceiver receiver = getOsgiService(SyslogReceiver.class, 10000);
        assertNotNull("the blueprint did not register a SyslogReceiver service", receiver);

        // The service arrives as a JDK proxy, so its concrete type is not observable here.
        // getName() is, and it reports the class name plus the address the bean was
        // configured with, which is what proves the .cfg properties were converted and
        // reached SyslogTcpConfig.
        assertTrue("unexpected receiver name: " + receiver.getName(),
                receiver.getName().startsWith(SyslogReceiverNettyTcpImpl.class.getSimpleName()));
        assertTrue("unexpected receiver name: " + receiver.getName(),
                receiver.getName().contains("127.0.0.1:" + s_port));

        // The blueprint starts the receiver on its own thread, so give the bind a moment.
        Exception last = null;
        for (int i = 0; i < 100; i++) {
            try (Socket probe = new Socket("127.0.0.1", s_port)) {
                assertTrue("could not connect to the listener", probe.isConnected());
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("the listener never bound on port " + s_port, last);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
