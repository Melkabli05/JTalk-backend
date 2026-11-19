package com.jtalk.livecommunication.integration.impl;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.zalando.problem.ThrowableProblem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real gateway, created by Micronaut (so its advice applies), against a media server that answers slowly: a room event must not make
 * the caller wait for it, and a call that must succeed must give up after the configured timeout instead of hanging.
 */
class LiveKitCallsTest extends ApiTestBase {

    private static final long SLOW_MS = 2_500;
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = startSlowMediaServer();

    @Inject
    ApplicationContext context;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new java.util.HashMap<>(super.getProperties());
        properties.put("livekit.url", "ws://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.put("livekit.request-timeout", "PT1S");
        return properties;
    }

    private static HttpServer startSlowMediaServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/", exchange -> {
                PATHS.add(exchange.getRequestURI().getPath());
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(SLOW_MS);
                    exchange.getResponseHeaders().add("Content-Type", "application/protobuf");
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException | IOException ignored) {
                    // the caller gave up first, which is the point of the timeout
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aRoomEventDoesNotMakeTheCallerWaitForTheMediaServer() {
        LiveKitGatewayImpl gateway = context.createBean(LiveKitGatewayImpl.class);
        assertTrue(gateway.getClass().getSimpleName().contains("Intercepted"), "the real bean, with its @Async advice: " + gateway.getClass());
        PATHS.clear();

        long started = System.nanoTime();
        gateway.broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.STAGE_UPDATED, UUID.randomUUID(), null, Instant.now(), null, null, null, null, null));
        long callerWaitedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(callerWaitedMs < 500, "the caller was held for " + callerWaitedMs + " ms by a media server that takes " + SLOW_MS + " ms");
        await("the event still reaches the media server", 5_000, () -> PATHS.stream().anyMatch(path -> path.endsWith("/SendData")));
    }

    @Test
    void aCallThatMustSucceedGivesUpAtTheTimeoutInsteadOfHanging() {
        LiveKitGatewayImpl gateway = context.createBean(LiveKitGatewayImpl.class);

        long started = System.nanoTime();
        ThrowableProblem problem = assertThrows(ThrowableProblem.class, () -> gateway.setPublishPermission(UUID.randomUUID(), UUID.randomUUID(), true));
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals("urn:jtalk:live-communication:problem:media-server-unreachable", problem.getType().toString());
        assertTrue(waitedMs >= 900 && waitedMs < SLOW_MS - 300, "gave up after " + waitedMs + " ms; the timeout is 1000 ms and the server needs " + SLOW_MS);
    }
}
