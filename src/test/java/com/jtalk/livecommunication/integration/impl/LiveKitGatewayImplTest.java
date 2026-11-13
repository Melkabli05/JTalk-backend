package com.jtalk.livecommunication.integration.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jtalk.livecommunication.config.LiveKitProperties;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
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
 * Tests the real LiveKit gateway against an in-process HTTP server that speaks LiveKit's protobuf API. The Retrofit client
 * only sends a request when its {@code Call} is executed, so these tests prove every operation really goes over the wire
 * with the right payload, and that LiveKit errors are decoded.
 */
class LiveKitGatewayImplTest {

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
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

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

    private LiveKitGatewayImpl gateway() {
        String host = "http://localhost:" + server.getAddress().getPort();
        var properties = new LiveKitProperties("ws://localhost:" + server.getAddress().getPort(), API_KEY, API_SECRET, Duration.ofHours(1), 300);
        return new LiveKitGatewayImpl(properties, RoomServiceClient.createClient(host, API_KEY, API_SECRET), Clock.fixed(NOW, ZoneOffset.UTC),
                io.micronaut.serde.ObjectMapper.getDefault());
    }

    private Received onlyRequest() {
        assertEquals(1, requests.size(), "expected exactly one request to LiveKit but got " + requests.size());
        return requests.getFirst();
    }

    // ---- operations really reach LiveKit, with the right payload ----------------------------------

    @Test
    void createRoomSendsTheEmptyRoomTimeout() throws Exception {
        gateway().createRoom(room);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/CreateRoom"), request.path());
        var body = LivekitRoom.CreateRoomRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getName());
        assertEquals(300, body.getEmptyTimeout());
    }

    @Test
    void deleteRoomSendsDeleteRoomSignedWithTheRoomCreateGrant() {
        gateway().deleteRoom(room);

        Received request = onlyRequest();
        assertEquals("POST", request.method());
        assertTrue(request.path().endsWith("/DeleteRoom"), request.path());
        assertTrue(request.authorization().startsWith("Bearer "), "server API calls carry a signed JWT");
        DecodedJWT jwt = JWT.decode(request.authorization().substring("Bearer ".length()));
        assertEquals(API_KEY, jwt.getIssuer());
        assertEquals(true, jwt.getClaim("video").asMap().get("roomCreate"), "DeleteRoom needs the roomCreate grant");
    }

    @Test
    void setPublishPermissionSendsTheRequestedPermissions() throws Exception {
        gateway().setPublishPermission(room, alice, true);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/UpdateParticipant"), request.path());
        var body = LivekitRoom.UpdateParticipantRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(alice.toString(), body.getIdentity());
        assertTrue(body.getPermission().getCanPublish());
        assertTrue(body.getPermission().getCanSubscribe());
    }

    @Test
    void revokingPublishRightsIsSentToo() throws Exception {
        gateway().setPublishPermission(room, alice, false);

        var body = LivekitRoom.UpdateParticipantRequest.parseFrom(onlyRequest().body());
        assertFalse(body.getPermission().getCanPublish());
        assertTrue(body.getPermission().getCanSubscribe(), "everyone keeps listening");
    }

    @Test
    void removeParticipantDisconnectsThemAndRevokesTheirEarlierTokens() throws Exception {
        gateway().removeParticipant(room, bob);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/RemoveParticipant"), request.path());
        var body = LivekitRoom.RoomParticipantIdentity.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(bob.toString(), body.getIdentity());
        assertEquals(NOW.getEpochSecond(), body.getRevokeTokenTs(), "tokens issued before the kick must stop working; LiveKit wants Unix seconds");
    }

    @Test
    void updateSubscriptionsSendsTheTracksAndSkipsEmptyLists() throws Exception {
        LiveKitGatewayImpl gateway = gateway();
        gateway.updateSubscriptions(room, bob, List.of(), true);
        assertEquals(0, requests.size(), "nothing to change, nothing sent");

        gateway.updateSubscriptions(room, bob, List.of("TR_a", "TR_b"), false);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/UpdateSubscriptions"), request.path());
        var body = LivekitRoom.UpdateSubscriptionsRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(bob.toString(), body.getIdentity());
        assertEquals(List.of("TR_a", "TR_b"), body.getTrackSidsList());
        assertFalse(body.getSubscribe());
    }

    /** Reads the SDK's deprecated SIDs field on purpose: it must stay empty, since SIDs and identities are easy to swap. */
    @SuppressWarnings("deprecation")
    @Test
    void sendDataTargetsIdentitiesNotSids() throws Exception {
        gateway().sendData(room, "whisper", Map.of("action", "start"), List.of(alice, bob));

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/SendData"), request.path());
        var body = LivekitRoom.SendDataRequest.parseFrom(request.body());
        assertEquals(List.of(alice.toString(), bob.toString()), body.getDestinationIdentitiesList());
        assertEquals(List.of(), body.getDestinationSidsList(), "the SDK takes SIDs before identities; they must not be swapped");
        assertEquals("whisper", body.getTopic());
        assertEquals(LivekitModels.DataPacket.Kind.RELIABLE, body.getKind());
        assertEquals("{\"action\":\"start\"}", body.getData().toStringUtf8());
    }

    @Test
    void broadcastRoomEventSendsOneDataMessageToTheWholeRoom() throws Exception {
        gateway().broadcastRoomEvent(room, new RoomRealtimeEvent(RoomEventType.HAND_RAISED, room, bob, NOW));

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/SendData"), request.path());
        var body = LivekitRoom.SendDataRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(List.of(), body.getDestinationIdentitiesList(), "no destination means everyone in the room");
        assertEquals("room-events", body.getTopic());
        String json = body.getData().toStringUtf8();
        assertTrue(json.contains("\"type\":\"HAND_RAISED\"") && json.contains(bob.toString()), json);
    }

    @Test
    void aFailedBroadcastNeverBreaksTheRequestThatCausedIt() {
        status = 400;
        contentType = "application/json";
        responseBody = "{\"code\":\"invalid_argument\",\"msg\":\"nope\"}".getBytes();

        assertDoesNotThrow(() -> gateway().broadcastRoomEvent(room, new RoomRealtimeEvent(RoomEventType.STAGE_UPDATED, room, null, NOW)));
        server.stop(0);
        assertDoesNotThrow(() -> gateway().broadcastRoomEvent(room, new RoomRealtimeEvent(RoomEventType.STAGE_UPDATED, room, null, NOW)));
    }

    // ---- error handling ---------------------------------------------------------------------------

    @Test
    void liveKitErrorsAreDecodedIntoTheProblemDetail() {
        status = 400;
        contentType = "application/json";
        responseBody = "{\"code\":\"invalid_argument\",\"msg\":\"room name invalid\"}".getBytes();

        ThrowableProblem problem = assertThrows(ThrowableProblem.class, () -> gateway().deleteRoom(room));

        assertEquals("urn:jtalk:live-communication:problem:media-server-error", problem.getType().toString());
        assertEquals(502, problem.getStatus().getStatusCode());
        assertTrue(problem.getDetail().contains("invalid_argument: room name invalid"), problem.getDetail());
    }

    @Test
    void notFoundMeansNothingToDoAndIsNotAnError() {
        status = 404;
        contentType = "application/json";
        responseBody = "{\"code\":\"not_found\",\"msg\":\"no such room\"}".getBytes();
        LiveKitGatewayImpl gateway = gateway();

        assertDoesNotThrow(() -> gateway.deleteRoom(room));
        assertDoesNotThrow(() -> gateway.setPublishPermission(room, bob, true));
    }

    @Test
    void anUnreachableServerBecomesABadGatewayProblem() {
        LiveKitGatewayImpl gateway = gateway();
        server.stop(0);

        ThrowableProblem problem = assertThrows(ThrowableProblem.class, () -> gateway.deleteRoom(room));

        assertEquals("urn:jtalk:live-communication:problem:media-server-unreachable", problem.getType().toString());
        assertEquals(502, problem.getStatus().getStatusCode());
    }

    // ---- join tokens ------------------------------------------------------------------------------

    @Test
    void joinTokenCarriesIdentityRoomPublishRightsAndExpiry() {
        String listener = gateway().generateToken(room, bob, false);
        String speaker = gateway().generateToken(room, alice, true);

        DecodedJWT listenerJwt = JWT.decode(listener);
        Map<String, Object> grants = listenerJwt.getClaim("video").asMap();
        assertEquals(bob.toString(), listenerJwt.getSubject());
        assertEquals(room.toString(), grants.get("room"));
        assertEquals(true, grants.get("roomJoin"));
        assertEquals(false, grants.get("canPublish"));
        assertEquals(true, grants.get("canSubscribe"));
        assertEquals(NOW.plus(Duration.ofHours(1)).getEpochSecond(), listenerJwt.getExpiresAt().toInstant().getEpochSecond());
        assertEquals(true, JWT.decode(speaker).getClaim("video").asMap().get("canPublish"));
        assertEquals(0, requests.size(), "issuing a token is local; it must not call LiveKit");
    }

    @Test
    void whisperRoomsArePrivateAndEachSideGetsOnlyItsOwnRights() {
        LiveKitGatewayImpl gateway = gateway();
        Map<String, Object> speaker = JWT.decode(gateway.generateWhisperToken(room, alice, bob, alice)).getClaim("video").asMap();
        Map<String, Object> target = JWT.decode(gateway.generateWhisperToken(room, alice, bob, bob)).getClaim("video").asMap();

        assertEquals("whisper-" + room + "-" + alice + "-" + bob, speaker.get("room"));
        assertEquals(speaker.get("room"), target.get("room"), "both sides meet in the same private room");
        assertEquals(true, speaker.get("canPublish"));
        assertEquals(false, speaker.get("canSubscribe"));
        assertEquals(false, target.get("canPublish"));
        assertEquals(true, target.get("canSubscribe"));
        assertEquals(0, requests.size(), "issuing a token is local");
    }

    @Test
    void closingAWhisperDeletesOnlyItsPrivateRoom() throws Exception {
        gateway().deleteWhisperRoom(room, alice, bob);

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/DeleteRoom"), request.path());
        assertEquals("whisper-" + room + "-" + alice + "-" + bob, LivekitRoom.DeleteRoomRequest.parseFrom(request.body()).getRoom());
    }
}
