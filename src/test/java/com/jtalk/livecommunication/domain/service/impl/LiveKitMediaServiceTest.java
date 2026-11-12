package com.jtalk.livecommunication.domain.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jtalk.livecommunication.config.LiveKitProperties;
import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;
import com.sun.net.httpserver.HttpServer;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import livekit.LivekitRoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalando.problem.ThrowableProblem;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the real LiveKit adapter against an in-process HTTP server that speaks LiveKit's protobuf API. The
 * Retrofit client only sends a request when its {@code Call} is executed, so these tests prove every
 * operation really goes over the wire with the right payload, and that LiveKit errors are decoded.
 */
class LiveKitMediaServiceTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-test-secret-test-secret-0123";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private record Received(String method, String path, String authorization, byte[] body) {
    }

    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String contentType = "application/protobuf";
    private volatile byte[] responseBody = new byte[0];
    private HttpServer server;
    private final UUID room = UUID.randomUUID();

    @BeforeEach
    void startFakeLiveKit() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeLiveKit() {
        server.stop(0);
    }

    private LiveKitMediaService service() {
        String host = "http://localhost:" + server.getAddress().getPort();
        var properties = new LiveKitProperties("ws://localhost:" + server.getAddress().getPort(), API_KEY, API_SECRET, Duration.ofHours(1));
        return new LiveKitMediaService(properties,
                RoomServiceClient.createClient(host, API_KEY, API_SECRET),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Received onlyRequest() {
        assertEquals(1, requests.size(), "expected exactly one request to LiveKit but got " + requests.size());
        return requests.getFirst();
    }

    // ---- operations really reach LiveKit, with the right payload ----------------------------------

    @Test
    void closeRoomSendsDeleteRoomSignedWithTheRoomCreateGrant() {
        service().closeRoom(room);

        Received request = onlyRequest();
        assertEquals("POST", request.method());
        assertTrue(request.path().endsWith("/DeleteRoom"), request.path());
        assertTrue(request.authorization().startsWith("Bearer "), "server API calls carry a signed JWT");
        DecodedJWT jwt = JWT.decode(request.authorization().substring("Bearer ".length()));
        assertEquals(API_KEY, jwt.getIssuer());
        assertEquals(true, jwt.getClaim("video").asMap().get("roomCreate"), "DeleteRoom needs the roomCreate grant");
    }

    @Test
    void updatePublishPermissionSendsTheRequestedPermissions() throws Exception {
        service().updatePublishPermission(room, "alice", true);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/UpdateParticipant"), request.path());
        var body = LivekitRoom.UpdateParticipantRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals("alice", body.getIdentity());
        assertTrue(body.getPermission().getCanPublish());
        assertTrue(body.getPermission().getCanSubscribe());
    }

    @Test
    void revokingPublishRightsIsSentToo() throws Exception {
        service().updatePublishPermission(room, "alice", false);

        var body = LivekitRoom.UpdateParticipantRequest.parseFrom(onlyRequest().body());
        assertFalse(body.getPermission().getCanPublish());
        assertTrue(body.getPermission().getCanSubscribe(), "everyone keeps listening");
    }

    @Test
    void setSubscriptionsSendsUpdateSubscriptionsAndSkipsEmptyLists() throws Exception {
        LiveKitMediaService service = service();
        service.setSubscriptions(room, "bob", List.of(), true);
        assertEquals(0, requests.size(), "nothing to change, nothing sent");

        service.setSubscriptions(room, "bob", List.of("TR_a", "TR_b"), false);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/UpdateSubscriptions"), request.path());
        var body = LivekitRoom.UpdateSubscriptionsRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals("bob", body.getIdentity());
        assertEquals(List.of("TR_a", "TR_b"), body.getTrackSidsList());
        assertFalse(body.getSubscribe());
    }

    @Test
    void sendSignalTargetsIdentitiesNotSids() throws Exception {
        byte[] payload = "{\"action\":\"start\"}".getBytes();

        service().sendSignal(room, "whisper", payload, List.of("alice", "bob"));

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/SendData"), request.path());
        var body = LivekitRoom.SendDataRequest.parseFrom(request.body());
        assertEquals(List.of("alice", "bob"), body.getDestinationIdentitiesList());
        assertEquals(List.of(), body.getDestinationSidsList(), "the SDK takes SIDs before identities; they must not be swapped");
        assertEquals("whisper", body.getTopic());
        assertEquals(LivekitModels.DataPacket.Kind.RELIABLE, body.getKind());
        assertEquals(new String(payload), body.getData().toStringUtf8());
    }

    // ---- error handling ---------------------------------------------------------------------------

    @Test
    void liveKitErrorsAreDecodedIntoTheProblemDetail() {
        status = 400;
        contentType = "application/json";
        responseBody = "{\"code\":\"invalid_argument\",\"msg\":\"room name invalid\"}".getBytes();

        ThrowableProblem problem = assertThrows(ThrowableProblem.class, () -> service().closeRoom(room));

        assertEquals("urn:jtalk:live-communication:problem:media-server-error", problem.getType().toString());
        assertEquals(502, problem.getStatus().getStatusCode());
        assertTrue(problem.getDetail().contains("invalid_argument: room name invalid"), problem.getDetail());
    }

    @Test
    void notFoundMeansNothingToDoAndIsNotAnError() {
        status = 404;
        contentType = "application/json";
        responseBody = "{\"code\":\"not_found\",\"msg\":\"no such room\"}".getBytes();
        LiveKitMediaService service = service();

        assertDoesNotThrow(() -> service.closeRoom(room));
        assertDoesNotThrow(() -> service.updatePublishPermission(room, "ghost", true));
    }

    @Test
    void anUnreachableServerBecomesABadGatewayProblem() {
        LiveKitMediaService service = service();
        server.stop(0);

        ThrowableProblem problem = assertThrows(ThrowableProblem.class, () -> service.closeRoom(room));

        assertEquals("urn:jtalk:live-communication:problem:media-server-unreachable", problem.getType().toString());
        assertEquals(502, problem.getStatus().getStatusCode());
    }

    // ---- join tokens ------------------------------------------------------------------------------

    @Test
    void joinTokenCarriesIdentityRoomPublishRightsAndExpiry() {
        MediaConnection listener = service().issueConnection(room, "bob", false);
        MediaConnection speaker = service().issueConnection(room, "alice", true);

        DecodedJWT listenerJwt = JWT.decode(listener.token());
        Map<String, Object> grants = listenerJwt.getClaim("video").asMap();
        assertEquals("bob", listenerJwt.getSubject());
        assertEquals(room.toString(), grants.get("room"));
        assertEquals(true, grants.get("roomJoin"));
        assertEquals(false, grants.get("canPublish"));
        assertEquals(true, grants.get("canSubscribe"));
        assertEquals(NOW.plus(Duration.ofHours(1)).getEpochSecond(), listenerJwt.getExpiresAt().toInstant().getEpochSecond());

        assertEquals(true, JWT.decode(speaker.token()).getClaim("video").asMap().get("canPublish"));
        assertEquals("ws://localhost:" + server.getAddress().getPort(), listener.url());
        assertEquals(0, requests.size(), "issuing a token is local; it must not call LiveKit");
    }
}
