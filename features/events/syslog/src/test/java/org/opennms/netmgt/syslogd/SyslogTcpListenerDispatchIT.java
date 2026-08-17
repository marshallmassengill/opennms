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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;
import org.opennms.core.ipc.sink.api.AsyncDispatcher;
import org.opennms.netmgt.config.syslogd.SyslogTcpConfig;
import org.opennms.netmgt.syslogd.api.SyslogConnection;

/**
 * Pins how the listener is allowed to call the sink dispatcher.
 *
 * AsyncDispatcher.send() blocks when the sink queue is full and the module asks for
 * blockWhenFull, which SyslogSinkModule does. Calling it from a Netty event loop therefore
 * stalls that worker and every connection on it. That happened on a real Minion: exactly one
 * message per connection was ingested and then the socket went quiet with nothing logged.
 *
 * MockMessageDispatcherFactory cannot reproduce it, because the dispatcher it builds never
 * blocks in send(), so this test supplies its own.
 */
public class SyslogTcpListenerDispatchIT {

    private static final long TIMEOUT_SECONDS = 20;

    private SyslogTcpListener m_listener;

    @After
    public void tearDown() {
        if (m_listener != null) {
            m_listener.stop();
            m_listener = null;
        }
    }

    @Test(timeout = 60 * 1000)
    public void neverCallsTheDispatcherFromAnEventLoopThread() throws Exception {
        final RecordingDispatcher dispatcher = new RecordingDispatcher(0);
        final int port = start(dispatcher);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            write(socket, "<34>Oct 11 22:14:15 host app: one\n");
            assertEquals("one", dispatcher.nextMessage());
        }

        // Netty names its event loop threads nioEventLoopGroup-N-M. A dispatch from one of
        // those is the defect, because send() is allowed to block.
        for (final String thread : dispatcher.callingThreads()) {
            assertFalse("send() was called on an event loop thread: " + thread,
                    thread.startsWith("nioEventLoopGroup"));
        }
    }

    @Test(timeout = 60 * 1000)
    public void keepsIngestingWhenTheSinkBlocks() throws Exception {
        // Blocks the first send until released, which is what a full sink queue does. With
        // the dispatch on the event loop this delivered the first message and then nothing.
        final RecordingDispatcher dispatcher = new RecordingDispatcher(1);
        final int port = start(dispatcher);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            final StringBuilder burst = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                burst.append("<34>Oct 11 22:14:15 host app: message ").append(i).append('\n');
            }
            write(socket, burst.toString());

            assertEquals("message 0", dispatcher.nextMessage());
            dispatcher.release();

            for (int i = 1; i < 5; i++) {
                assertEquals("message " + i, dispatcher.nextMessage());
            }
        }
    }

    // --- harness ------------------------------------------------------------

    private int start(final AsyncDispatcher<SyslogConnection> dispatcher) throws Exception {
        final int port = findFreePort();

        final SyslogTcpConfig config = new SyslogTcpConfig();
        config.setPort(port);
        config.setListenAddress("127.0.0.1");
        config.setFraming("non-transparent");

        m_listener = new SyslogTcpListener(config, dispatcher);
        m_listener.start();
        assertTrue("the listener did not bind", m_listener.isStarted());
        return port;
    }

    /**
     * Records the thread each send() came in on, and optionally blocks the first few sends
     * the way a full sink queue would.
     */
    private static class RecordingDispatcher implements AsyncDispatcher<SyslogConnection> {

        private final Set<String> callingThreads = ConcurrentHashMap.newKeySet();
        private final LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final int blockFirst;

        private int seen;

        RecordingDispatcher(final int blockFirst) {
            this.blockFirst = blockFirst;
        }

        @Override
        public CompletableFuture<DispatchStatus> send(final SyslogConnection message) {
            callingThreads.add(Thread.currentThread().getName());

            final boolean block;
            synchronized (this) {
                block = ++seen <= blockFirst;
            }

            final ByteBuffer buffer = message.getBuffer();
            final byte[] bytes = new byte[buffer.limit()];
            buffer.duplicate().rewind().get(bytes);
            delivered.add(new String(bytes, StandardCharsets.UTF_8).replaceAll("^<\\d+>.*app: ", ""));

            if (block) {
                try {
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return CompletableFuture.completedFuture(DispatchStatus.DISPATCHED);
        }

        void release() {
            release.countDown();
        }

        String nextMessage() throws InterruptedException {
            final String message = delivered.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("timed out waiting for a dispatched message", message);
            return message;
        }

        List<String> callingThreads() {
            return new ArrayList<>(callingThreads);
        }

        @Override
        public int getQueueSize() {
            return 0;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private static void write(final Socket socket, final String content) throws Exception {
        socket.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
