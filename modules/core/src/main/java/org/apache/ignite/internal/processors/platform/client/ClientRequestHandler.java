/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.platform.client;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteIllegalStateException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.internal.IgniteFutureTimeoutCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.binary.BinaryWriterEx;
import org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode;
import org.apache.ignite.internal.processors.odbc.ClientAsyncResponse;
import org.apache.ignite.internal.processors.odbc.ClientListenerProtocolVersion;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequest;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequestHandler;
import org.apache.ignite.internal.processors.odbc.ClientListenerResponse;
import org.apache.ignite.internal.processors.odbc.SqlListenerUtils;
import org.apache.ignite.internal.processors.platform.client.cache.ClientCacheQueryNextPageRequest;
import org.apache.ignite.internal.processors.platform.client.cache.ClientCacheSqlFieldsQueryRequest;
import org.apache.ignite.internal.processors.platform.client.cache.ClientCacheSqlQueryRequest;
import org.apache.ignite.internal.processors.platform.client.tx.ClientTxAwareRequest;
import org.apache.ignite.internal.processors.platform.client.tx.ClientTxContext;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.security.SecurityException;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_THIN_CLIENT_ASYNC_REQUESTS_WAIT_TIMEOUT;
import static org.apache.ignite.internal.processors.platform.client.ClientProtocolVersionFeature.BITMAP_FEATURES;
import static org.apache.ignite.internal.processors.platform.client.ClientProtocolVersionFeature.PARTITION_AWARENESS;

/**
 * Thin client request handler.
 */
public class ClientRequestHandler implements ClientListenerRequestHandler {
    /** Timeout to wait for async requests completion, to handle them as regular sync requests. */
    public static final long DFLT_ASYNC_REQUEST_WAIT_TIMEOUT_MILLIS = 10L;

    /** */
    private final long asyncReqWaitTimeout = IgniteSystemProperties.getLong(
        IGNITE_THIN_CLIENT_ASYNC_REQUESTS_WAIT_TIMEOUT,
        DFLT_ASYNC_REQUEST_WAIT_TIMEOUT_MILLIS
    );

    /** Client context. */
    private final ClientConnectionContext ctx;

    /** Protocol context. */
    private final ClientProtocolContext protocolCtx;

    /** Logger. */
    private final IgniteLogger log;

    /**
     * Constructor.
     *
     * @param ctx Kernal context.
     * @param protocolCtx Protocol context.
     */
    ClientRequestHandler(ClientConnectionContext ctx, ClientProtocolContext protocolCtx) {
        assert ctx != null;

        this.ctx = ctx;
        this.protocolCtx = protocolCtx;
        log = ctx.kernalContext().log(getClass());
    }

    /** {@inheritDoc} */
    @Override public ClientListenerResponse handle(ClientListenerRequest req) {
        try {
            if (req instanceof ClientTxAwareRequest) {
                ClientTxAwareRequest req0 = (ClientTxAwareRequest)req;

                if (req0.isTransactional()) {
                    int txId = req0.txId();

                    ClientTxContext txCtx = ctx.txContext(txId);

                    if (txCtx != null) {
                        try {
                            txCtx.acquire(true);

                            return handle0(req);
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteClientException(ClientStatus.FAILED, e.getMessage(), e);
                        }
                        finally {
                            try {
                                txCtx.release(true);
                            }
                            catch (Exception e) {
                                log.warning("Failed to release client transaction context", e);
                            }
                        }
                    }
                }
            }

            return handle0(req);
        }
        catch (SecurityException ex) {
            throw IgniteClientException.wrapAuthorizationExeption(ex);
        }
    }

    /** */
    private ClientListenerResponse handle0(ClientListenerRequest req) {
        ClientRequest req0 = (ClientRequest)req;

        if (req0.isAsync(ctx)) {
            IgniteInternalFuture<ClientResponse> fut = req0.processAsync(ctx);

            if (asyncReqWaitTimeout <= 0)
                return new ClientAsyncResponse(req0.requestId(), fut);

            try {
                // Give request a chance to be executed and response processed by the current thread,
                // so we can avoid any performance drops caused by async requests execution.
                return fut.get(asyncReqWaitTimeout);
            }
            catch (IgniteFutureTimeoutCheckedException ignored) {
                return new ClientAsyncResponse(req0.requestId(), fut);
            }
            catch (IgniteCheckedException e) {
                throw new IgniteClientException(ClientStatus.FAILED, e.getMessage(), e);
            }
        }
        else
            return req0.process(ctx);
    }

    /** {@inheritDoc} */
    @Override public ClientListenerResponse handleException(Throwable e, ClientListenerRequest req) {
        assert req != null;
        assert e != null;

        int status = getStatus(e);
        String msg = e.getMessage();

        if (req instanceof ClientCacheSqlQueryRequest ||
            req instanceof ClientCacheSqlFieldsQueryRequest ||
            req instanceof ClientCacheQueryNextPageRequest) {

            String sqlState = IgniteQueryErrorCode.codeToSqlState(SqlListenerUtils.exceptionToSqlErrorCode(e));
            msg = sqlState + ": " + msg;
        }

        if (ctx.kernalContext().clientListener().sendServerExceptionStackTraceToClient())
            msg = msg + U.nl() + X.getFullStackTrace(e);

        return new ClientResponse(req.requestId(), status, msg);
    }

    /** {@inheritDoc} */
    @Override public void writeHandshake(BinaryWriterEx writer) {
        writer.writeBoolean(true);

        if (protocolCtx.isFeatureSupported(BITMAP_FEATURES))
            writer.writeByteArray(protocolCtx.featureBytes());

        if (protocolCtx.isFeatureSupported(PARTITION_AWARENESS))
            writer.writeUuid(ctx.kernalContext().localNodeId());
    }

    /** {@inheritDoc} */
    @Override public boolean isCancellationCommand(int cmdId) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isCancellationSupported() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public void registerRequest(long reqId, int cmdType) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void unregisterRequest(long reqId) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public ClientListenerProtocolVersion protocolVersion() {
        return protocolCtx.version();
    }

    /**
     * Gets the status based on the provided exception.
     *
     * @param e Exception.
     * @return Status code.
     */
    private int getStatus(Throwable e) {
        if (e instanceof IgniteClientException)
            return ((IgniteClientException)e).statusCode();

        if (e instanceof IgniteIllegalStateException) {
            IgniteIllegalStateException ex = (IgniteIllegalStateException)e;

            if (ex.getMessage().startsWith("Grid is in invalid state"))
                return ClientStatus.INVALID_NODE_STATE;
        }

        if (e instanceof IllegalStateException) {
            IllegalStateException ex = (IllegalStateException)e;

            if (ex.getMessage().contains("grid is stopping"))
                return ClientStatus.INVALID_NODE_STATE;
        }

        return ClientStatus.FAILED;
    }
}
