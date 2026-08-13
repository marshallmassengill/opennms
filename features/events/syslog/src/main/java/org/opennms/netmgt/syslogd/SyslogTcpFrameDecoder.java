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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

import org.opennms.netmgt.config.syslogd.SyslogTcpFraming;
import org.opennms.netmgt.syslogd.api.SyslogConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ByteProcessor;

/**
 * Splits a syslog TCP stream into individual messages, emitting one
 * {@link SyslogConnection} per message.
 *
 * RFC 6587 defines two incompatible framings and senders disagree about which to
 * use by default, so both are supported. In {@link SyslogTcpFraming#AUTO} the
 * framing is detected from the first frame and then latched for the life of the
 * connection: a stream that changes framing mid-flight cannot be decoded
 * unambiguously, so guessing per frame would turn a sender bug into corrupt events.
 *
 * One instance per channel. The decoder is not thread safe, which matches Netty's
 * per-channel handler contract.
 */
public class SyslogTcpFrameDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(SyslogTcpFrameDecoder.class);

    /**
     * A length prefix longer than this cannot be a legitimate MSG-LEN. Bounding the
     * scan keeps a sender that never emits the separating space from growing the
     * cumulation buffer without limit.
     */
    private static final int MAX_LENGTH_PREFIX_DIGITS = 10;

    private static final byte SPACE = ' ';
    private static final byte LF = '\n';
    private static final byte CR = '\r';
    private static final byte NUL = 0;

    private final InetSocketAddress source;
    private final SyslogTcpFraming configuredFraming;
    private final int maxMessageSize;

    private SyslogTcpFraming activeFraming;

    public SyslogTcpFrameDecoder(final InetSocketAddress source, final SyslogTcpFraming configuredFraming, final int maxMessageSize) {
        this.source = Objects.requireNonNull(source);
        this.configuredFraming = Objects.requireNonNull(configuredFraming);
        if (maxMessageSize < 1) {
            throw new IllegalArgumentException("maxMessageSize must be positive");
        }
        this.maxMessageSize = maxMessageSize;
        this.activeFraming = configuredFraming == SyslogTcpFraming.AUTO ? null : configuredFraming;
    }

    /**
     * The framing in use for this connection, or null if auto-detection has not seen
     * a frame yet.
     */
    public SyslogTcpFraming getActiveFraming() {
        return activeFraming;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
        if (activeFraming == null) {
            // Empty frames carry no framing signal and would otherwise latch a stream
            // of keepalives into non-transparent framing before the first real message.
            skipEmptyFrameTrailers(in);
            if (!in.isReadable()) {
                return;
            }
            final byte first = in.getByte(in.readerIndex());
            activeFraming = (first >= '0' && first <= '9')
                    ? SyslogTcpFraming.OCTET_COUNTING
                    : SyslogTcpFraming.NON_TRANSPARENT;
            LOG.info("Detected {} framing for syslog TCP connection from {}", activeFraming, source);
        }

        if (activeFraming == SyslogTcpFraming.OCTET_COUNTING) {
            decodeOctetCounting(in, out);
        } else {
            decodeNonTransparent(in, out);
        }
    }

    /**
     * RFC 6587 section 3.4.1. The length prefix counts only SYSLOG-MSG, so the frame
     * is not complete until the prefix, the space and MSG-LEN further octets have all
     * arrived.
     */
    private void decodeOctetCounting(final ByteBuf in, final List<Object> out) {
        while (true) {
            final int start = in.readerIndex();
            final int scanLimit = Math.min(in.writerIndex(), start + MAX_LENGTH_PREFIX_DIGITS + 1);

            int spaceIndex = -1;
            for (int i = start; i < scanLimit; i++) {
                final byte b = in.getByte(i);
                if (b == SPACE) {
                    spaceIndex = i;
                    break;
                }
                if (b < '0' || b > '9') {
                    throw new CorruptedFrameException(String.format(
                            "Malformed octet-counted syslog frame from %s: unexpected byte 0x%02X in the length prefix",
                            source, b));
                }
            }

            if (spaceIndex < 0) {
                if (scanLimit - start > MAX_LENGTH_PREFIX_DIGITS) {
                    throw new CorruptedFrameException(String.format(
                            "Malformed octet-counted syslog frame from %s: no separator within %d bytes of the length prefix",
                            source, MAX_LENGTH_PREFIX_DIGITS));
                }
                return;
            }

            if (spaceIndex == start) {
                throw new CorruptedFrameException(String.format(
                        "Malformed octet-counted syslog frame from %s: empty length prefix", source));
            }

            // Accumulate into a long so that a 10 digit prefix cannot overflow before
            // it is compared against the configured maximum.
            long messageLength = 0;
            for (int i = start; i < spaceIndex; i++) {
                messageLength = messageLength * 10 + (in.getByte(i) - '0');
            }

            if (messageLength > maxMessageSize) {
                throw new TooLongFrameException(String.format(
                        "Octet-counted syslog frame from %s declares %d bytes, which exceeds the %d byte maximum",
                        source, messageLength, maxMessageSize));
            }

            final int headerLength = spaceIndex + 1 - start;
            if (messageLength == 0) {
                // Not legal per the grammar, but harmless. Consume the header so that the
                // loop makes progress rather than spinning on the same bytes.
                in.skipBytes(headerLength);
                LOG.debug("Discarding zero length octet-counted syslog frame from {}", source);
                continue;
            }

            if (in.readableBytes() < headerLength + messageLength) {
                return;
            }

            in.skipBytes(headerLength);
            out.add(toConnection(in, in.readerIndex(), (int) messageLength));
            in.skipBytes((int) messageLength);
        }
    }

    /**
     * RFC 6587 section 3.4.2. LF is the trailer in practice; CR and NUL are tolerated
     * because senders in the wild append them and nothing downstream strips a CR.
     */
    private void decodeNonTransparent(final ByteBuf in, final List<Object> out) {
        while (true) {
            skipEmptyFrameTrailers(in);
            if (!in.isReadable()) {
                return;
            }

            final int start = in.readerIndex();
            final int lfIndex = in.forEachByte(start, in.readableBytes(), ByteProcessor.FIND_LF);

            if (lfIndex < 0) {
                if (in.readableBytes() > maxMessageSize) {
                    throw new TooLongFrameException(String.format(
                            "Syslog frame from %s exceeds the %d byte maximum with no trailer in sight",
                            source, maxMessageSize));
                }
                return;
            }

            final int frameLength = lfIndex - start;
            if (frameLength > maxMessageSize) {
                throw new TooLongFrameException(String.format(
                        "Syslog frame from %s is %d bytes, which exceeds the %d byte maximum",
                        source, frameLength, maxMessageSize));
            }

            final int trimmedLength = trimTrailingTrailers(in, start, frameLength);
            if (trimmedLength > 0) {
                out.add(toConnection(in, start, trimmedLength));
            }
            // Consume the frame and its LF whether or not it produced a message.
            in.skipBytes(frameLength + 1);
        }
    }

    /**
     * Advances past trailer bytes that leave nothing in front of them. rsyslog sends a
     * bare LF as a keepalive, which is not an error and must not produce an event.
     */
    private void skipEmptyFrameTrailers(final ByteBuf in) {
        while (in.isReadable()) {
            final byte b = in.getByte(in.readerIndex());
            if (b != LF && b != CR && b != NUL) {
                return;
            }
            in.skipBytes(1);
        }
    }

    private int trimTrailingTrailers(final ByteBuf in, final int start, final int length) {
        int trimmed = length;
        while (trimmed > 0) {
            final byte b = in.getByte(start + trimmed - 1);
            if (b != CR && b != NUL) {
                break;
            }
            trimmed--;
        }
        return trimmed;
    }

    /**
     * Copies the frame out of the Netty buffer because the sink dispatch is
     * asynchronous and the buffer is recycled as soon as decoding returns.
     *
     * The copy is allocated at exactly the message length: ByteBufferXmlAdapter
     * marshals the whole backing array, so any slack would be sent to the consumer as
     * trailing garbage.
     */
    private SyslogConnection toConnection(final ByteBuf in, final int index, final int length) {
        final ByteBuffer copy = ByteBuffer.allocate(length);
        in.getBytes(index, copy);
        copy.flip();
        return new SyslogConnection(source, copy);
    }
}
