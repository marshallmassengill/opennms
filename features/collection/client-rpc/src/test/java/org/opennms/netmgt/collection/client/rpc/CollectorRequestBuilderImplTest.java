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
package org.opennms.netmgt.collection.client.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.Scope;
import org.opennms.core.mate.api.TokenProvider;
import org.opennms.core.rpc.api.RpcClient;
import org.opennms.core.rpc.utils.RpcTargetHelper;
import org.opennms.netmgt.collection.api.AbstractServiceCollector;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionAuthFailureException;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.ServiceCollector;
import org.opennms.netmgt.collection.dto.CollectionAgentDTO;
import org.opennms.netmgt.collection.support.builder.CollectionSetBuilder;

/**
 * Unit tests for the controller-side single-retry path on auth failure
 * in {@link CollectorRequestBuilderImpl}.
 *
 * <p>Drives the builder against a stubbed RPC delegate that returns
 * scripted {@link CollectorResponseDTO}s on each call. Verifies the
 * builder invalidates the right cache entries via
 * {@link TokenProvider#invalidateByTokenValue(String)} and re-issues
 * the RPC exactly once on a 401/403 signal from the minion.</p>
 */
public class CollectorRequestBuilderImplTest {

    private CollectionAgentDTO agent;
    private ServiceCollector collector;
    private EntityScopeProvider entityScopeProvider;

    @Before
    public void setUp() throws Exception {
        agent = new CollectionAgentDTO();
        agent.setNodeId(1);
        agent.setLocationName("Default");
        agent.setAddress(InetAddress.getByName("10.0.0.1"));

        collector = new StubServiceCollector();
        entityScopeProvider = new EmptyEntityScopeProvider();
    }

    /** Successful response: handler returns the collection set, no retry. */
    @Test
    public void successfulResponseReturnsCollectionSetWithoutRetry() throws Exception {
        final CollectionSet expectedSet = new CollectionSetBuilder(agent).build();
        final ScriptedDelegate delegate = new ScriptedDelegate(
                Arrays.asList(new CollectorResponseDTO(expectedSet)));

        final CollectionSet actual = newBuilder(delegate, /*tokenProvider=*/null)
                .withCollector(collector)
                .withAgent(agent)
                .execute()
                .get();

        assertSame(expectedSet, actual);
        assertEquals("delegate should be called exactly once", 1, delegate.callCount.get());
    }

    /**
     * Auth-failure response: builder invalidates each carried header
     * value via TokenProvider, then issues a second RPC. On success,
     * returns the second response's collection set.
     */
    @Test
    public void authFailureTriggersInvalidationAndSingleRetry() throws Exception {
        final CollectionSet expectedSet = new CollectionSetBuilder(agent).build();

        final CollectorResponseDTO authFailureResponse = new CollectorResponseDTO();
        authFailureResponse.setAuthFailureStatusCode(401);
        authFailureResponse.setAuthFailureHeaderValues(Arrays.asList(
                "Bearer stale-token-value-1",
                "X-Auth-Token: stale-token-value-2"));

        final ScriptedDelegate delegate = new ScriptedDelegate(Arrays.asList(
                authFailureResponse,
                new CollectorResponseDTO(expectedSet)));

        final RecordingTokenProvider provider = new RecordingTokenProvider();

        final CollectionSet actual = newBuilder(delegate, provider)
                .withCollector(collector)
                .withAgent(agent)
                .execute()
                .get();

        assertSame(expectedSet, actual);
        assertEquals("delegate should be called twice (initial + retry)", 2, delegate.callCount.get());
        assertEquals("each carried header value should drive an invalidation",
                Arrays.asList("Bearer stale-token-value-1", "X-Auth-Token: stale-token-value-2"),
                provider.invalidatedValues);
    }

    /**
     * Two auth failures in a row: builder gives up and surfaces a
     * {@link CollectionAuthFailureException} rather than spin.
     */
    @Test
    public void secondAuthFailureSurfacesAsCollectionAuthFailureException() {
        final CollectorResponseDTO firstFailure = authFailure(401, Arrays.asList("Bearer t1"));
        final CollectorResponseDTO secondFailure = authFailure(403, Arrays.asList("Bearer t2"));

        final ScriptedDelegate delegate = new ScriptedDelegate(Arrays.asList(firstFailure, secondFailure));
        final RecordingTokenProvider provider = new RecordingTokenProvider();

        try {
            newBuilder(delegate, provider)
                    .withCollector(collector)
                    .withAgent(agent)
                    .execute()
                    .get();
            fail("expected CollectionAuthFailureException after second 401/403");
        } catch (final ExecutionException ee) {
            final Throwable cause = ee.getCause();
            assertNotNull(cause);
            assertTrue("expected CollectionAuthFailureException, got " + cause.getClass(),
                    cause instanceof CollectionAuthFailureException);
            final CollectionAuthFailureException afe = (CollectionAuthFailureException) cause;
            assertEquals(403, afe.getStatusCode());
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("interrupted: " + ie);
        }

        assertEquals(2, delegate.callCount.get());
    }

    /**
     * No TokenProvider wired: builder cannot invalidate anything, so it
     * surfaces the auth failure directly rather than retry.
     */
    @Test
    public void authFailureWithoutTokenProviderSurfacesImmediately() {
        final CollectorResponseDTO failure = authFailure(401,
                Arrays.asList("Bearer some-token"));
        final ScriptedDelegate delegate = new ScriptedDelegate(Collections.singletonList(failure));

        try {
            newBuilder(delegate, /*tokenProvider=*/null)
                    .withCollector(collector)
                    .withAgent(agent)
                    .execute()
                    .get();
            fail("expected CollectionAuthFailureException when no TokenProvider is wired");
        } catch (final ExecutionException ee) {
            assertTrue(ee.getCause() instanceof CollectionAuthFailureException);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("interrupted: " + ie);
        }

        assertEquals("no retry without a TokenProvider", 1, delegate.callCount.get());
    }

    /**
     * Auth-failure response with empty header-values list: the retry still
     * fires (the minion observed a 401, so the cached token is suspect)
     * even though there's nothing to invalidate by-value.
     */
    @Test
    public void authFailureWithEmptyHeaderValuesStillRetriesOnce() throws Exception {
        final CollectorResponseDTO firstFailure = new CollectorResponseDTO();
        firstFailure.setAuthFailureStatusCode(401);
        firstFailure.setAuthFailureHeaderValues(Collections.emptyList());

        final CollectionSet expected = new CollectionSetBuilder(agent).build();

        final ScriptedDelegate delegate = new ScriptedDelegate(Arrays.asList(
                firstFailure, new CollectorResponseDTO(expected)));
        final RecordingTokenProvider provider = new RecordingTokenProvider();

        final CollectionSet actual = newBuilder(delegate, provider)
                .withCollector(collector)
                .withAgent(agent)
                .execute()
                .get();

        assertSame(expected, actual);
        assertEquals(2, delegate.callCount.get());
        assertEquals("nothing to invalidate", Collections.emptyList(), provider.invalidatedValues);
    }

    // --- helpers ----------------------------------------------------------

    private CollectorRequestBuilderImpl newBuilder(final RpcClient<CollectorRequestDTO, CollectorResponseDTO> delegate,
                                                   final TokenProvider tokenProvider) {
        final LocationAwareCollectorClientImpl client = new LocationAwareCollectorClientImpl() {
            @Override
            protected RpcClient<CollectorRequestDTO, CollectorResponseDTO> getDelegate() { return delegate; }

            @Override
            public RpcTargetHelper getRpcTargetHelper() { return new RpcTargetHelper(); }

            @Override
            public EntityScopeProvider getEntityScopeProvider() { return entityScopeProvider; }

            @Override
            public TokenProvider getTokenProvider() { return tokenProvider; }
        };
        return new CollectorRequestBuilderImpl(client);
    }

    private static CollectorResponseDTO authFailure(final int statusCode, final List<String> headerValues) {
        final CollectorResponseDTO r = new CollectorResponseDTO();
        r.setAuthFailureStatusCode(statusCode);
        r.setAuthFailureHeaderValues(headerValues);
        return r;
    }

    /** RpcClient stub that returns scripted responses in order. */
    private static class ScriptedDelegate implements RpcClient<CollectorRequestDTO, CollectorResponseDTO> {
        final List<CollectorResponseDTO> responses;
        final AtomicInteger callCount = new AtomicInteger();

        ScriptedDelegate(final List<CollectorResponseDTO> responses) {
            this.responses = responses;
        }

        @Override
        public CompletableFuture<CollectorResponseDTO> execute(final CollectorRequestDTO request) {
            final int idx = callCount.getAndIncrement();
            if (idx >= responses.size()) {
                final CompletableFuture<CollectorResponseDTO> failed = new CompletableFuture<>();
                failed.completeExceptionally(
                        new IllegalStateException("delegate called more times than the script has responses"));
                return failed;
            }
            return CompletableFuture.completedFuture(responses.get(idx));
        }
    }

    /** TokenProvider stub that records each invalidate call. */
    private static class RecordingTokenProvider implements TokenProvider {
        final List<String> invalidatedValues = new ArrayList<>();

        @Override
        public Optional<String> getToken(final String authName) {
            return Optional.empty();
        }

        @Override
        public Optional<TokenProvider.InvalidationResult> invalidateByTokenValue(final String headerValue) {
            invalidatedValues.add(headerValue);
            return Optional.empty();
        }
    }

    /** Minimal ServiceCollector that surfaces no runtime attributes. */
    private static class StubServiceCollector extends AbstractServiceCollector {
        @Override public void initialize() { }
        @Override public CollectionSet collect(CollectionAgent agent, Map<String, Object> parameters) {
            return new CollectionSetBuilder(agent).build();
        }
        @Override public org.opennms.netmgt.rrd.RrdRepository getRrdRepository(String collectionName) { return null; }
    }

    /** EntityScopeProvider stub returning EmptyScope for all lookups. */
    private static class EmptyEntityScopeProvider implements EntityScopeProvider {
        @Override public Scope getScopeForScv() { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForEnv() { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForNode(Integer nodeId) { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForInterface(Integer nodeId, String ipAddress) { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForInterfaceByIfIndex(Integer nodeId, int ifIndex) { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForInterfaceByIfName(Integer nodeId, String ifName) { return EmptyScope.EMPTY; }
        @Override public Scope getScopeForService(Integer nodeId, java.net.InetAddress ipAddress, String serviceName) { return EmptyScope.EMPTY; }
    }
}
