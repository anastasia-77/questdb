/*
 * Copyright (c) 2014. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.journal.net;

import com.nfsdb.journal.exceptions.JournalNetworkException;
import com.nfsdb.journal.logging.Logger;
import com.nfsdb.journal.net.config.SslConfig;
import com.nfsdb.journal.utils.ByteBuffers;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

public class SslByteChannel implements ByteChannel {

    private static final Logger LOGGER = Logger.getLogger(SslByteChannel.class);


    private final ByteChannel underlying;
    private final SSLEngine engine;
    private final ByteBuffer inBuf;
    private final ByteBuffer outBuf;
    private final int sslDataLimit;
    private boolean inData = false;
    private SSLEngineResult.HandshakeStatus handshakeStatus = SSLEngineResult.HandshakeStatus.NEED_WRAP;
    private ByteBuffer swapBuf;
    private boolean client;
    private boolean fillInBuf = true;

    public SslByteChannel(ByteChannel underlying, SslConfig sslConfig) throws JournalNetworkException {
        this.underlying = underlying;
        SSLContext sslc = sslConfig.getSslContext();
        this.engine = sslc.createSSLEngine();
        this.engine.setEnableSessionCreation(true);
        this.engine.setUseClientMode(sslConfig.isClient());
        this.engine.setNeedClientAuth(sslConfig.isRequireClientAuth());
        this.client = sslConfig.isClient();

        SSLSession session = engine.getSession();
        this.sslDataLimit = session.getApplicationBufferSize();
        inBuf = ByteBuffer.allocateDirect(session.getPacketBufferSize());
        outBuf = ByteBuffer.allocateDirect(session.getPacketBufferSize());
        swapBuf = ByteBuffer.allocate(sslDataLimit * 2);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        handshake();
        int p = dst.position();

        while (true) {

            int limit = dst.remaining();
            if (limit == 0) {
                break;
            }

            // check if anything is remaining in swapBuf
            if (swapBuf.hasRemaining()) {

                ByteBuffers.copy(swapBuf, dst);

            } else {

                if (fillInBuf) {
                    // read from channel
                    inBuf.clear();
                    int result = underlying.read(inBuf);

                    if (result == -1) {
                        throw new IOException("Did not expect -1 from socket channel");
                    }

                    if (result == 0) {
                        throw new IOException("Blocking connection must not return 0");
                    }

                    inBuf.flip();
                }

                // dst is larger than minimum?
                if (limit < sslDataLimit) {
                    // no, dst is small, use swap
                    swapBuf.clear();
                    fillInBuf = unwrap(swapBuf);
                    swapBuf.flip();
                    ByteBuffers.copy(swapBuf, dst);
                } else {
                    fillInBuf = unwrap(dst);
                }
            }
        }
        return dst.position() - p;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        handshake();

        int count = src.remaining();
        while (src.hasRemaining()) {
            outBuf.clear();
            SSLEngineResult result = engine.wrap(src, outBuf);
            if (result.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("Expected OK, got: " + result.getStatus());
            }
            outBuf.flip();
            try {
                ByteBuffers.copy(outBuf, underlying);
            } catch (JournalNetworkException e) {
                throw new IOException(e);
            }
        }
        return count;
    }

    @Override
    public boolean isOpen() {
        return underlying.isOpen();
    }

    @Override
    public void close() throws IOException {
        underlying.close();
        if (client) {
            engine.closeOutbound();
        } else {
            engine.closeInbound();
        }
    }

    private void handshake() throws IOException {

        if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
            return;
        }

        engine.beginHandshake();

        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) {
            try {
                switch (handshakeStatus) {
                    case NOT_HANDSHAKING:
                        throw new IOException("Not handshaking");
                    case NEED_WRAP:
                        outBuf.clear();
                        swapBuf.clear();
                        handshakeStatus = engine.wrap(swapBuf, outBuf).getHandshakeStatus();
                        outBuf.flip();
                        underlying.write(outBuf);
                        break;
                    case NEED_UNWRAP:
                        if (!inData || !inBuf.hasRemaining()) {
                            inBuf.clear();
                            underlying.read(inBuf);
                            inBuf.flip();
                            inData = true;
                        }
                        handshakeStatus = engine.unwrap(inBuf, swapBuf).getHandshakeStatus();
                        break;
                    case NEED_TASK:
                        Runnable task;
                        while ((task = engine.getDelegatedTask()) != null) {
                            task.run();
                        }
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                }
            } catch (SSLException e) {
                if (e instanceof SSLHandshakeException) {
                    LOGGER.error("Handshake failed: %s", e.getMessage());
                    // this is server side error, continue talking to client
                    continue;
                }
                throw e;
            }
        }
        inBuf.clear();
        // make sure swapBuf starts by having remaining() == false
        swapBuf.position(swapBuf.limit());
    }

    private boolean unwrap(ByteBuffer dst) throws IOException {
        while (inBuf.hasRemaining()) {
            SSLEngineResult.Status status = engine.unwrap(inBuf, dst).getStatus();
            switch (status) {
                case BUFFER_UNDERFLOW:
                    inBuf.compact();
                    underlying.read(inBuf);
                    inBuf.flip();
                    break;
                case BUFFER_OVERFLOW:
                    return false;
                case OK:
                    break;
                case CLOSED:
                    throw new IOException("Did not expect CLOSED");
            }
        }
        return true;
    }
}
