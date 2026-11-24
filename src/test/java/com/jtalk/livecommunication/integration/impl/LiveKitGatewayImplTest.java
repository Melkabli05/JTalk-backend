package com.jtalk.livecommunication.integration.impl;

import com.jtalk.livecommunication.domain.model.ParticipantPreview;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jtalk.livecommunication.config.LiveKitConfig;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.StageState;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.sun.net.httpserver.HttpServer;
import io.livekit.server.RoomServiceClient;
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
        var properties = new LiveKitConfig("ws://localhost:" + server.getAddress().getPort(), API_KEY, API_SECRET, Duration.ofHours(1), 300, Duration.ofSeconds(8));
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
    void aSpeakerMayPublishAMicrophoneAndNothingElseAndNeverWriteToTheDataChannel() throws Exception {
        gateway().setPublishPermission(room, alice, true);

        var permission = LivekitRoom.UpdateParticipantRequest.parseFrom(onlyRequest().body()).getPermission();
        assertEquals(List.of(livekit.LivekitModels.TrackSource.MICROPHONE), permission.getCanPublishSourcesList(),
                "an empty list would let a modified client publish a camera or a screen share");
        assertFalse(permission.getCanPublishData(), "room events are sent by the service; a client must not be able to fake one");
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
    void muteMicrophoneLooksUpTheirTracksAndMutesOnlyTheMicrophone() throws Exception {
        responseBody = livekit.LivekitModels.ParticipantInfo.newBuilder()
                .setIdentity(bob.toString())
                .addTracks(livekit.LivekitModels.TrackInfo.newBuilder().setSid("TR_mic").setSource(livekit.LivekitModels.TrackSource.MICROPHONE))
                .addTracks(livekit.LivekitModels.TrackInfo.newBuilder().setSid("TR_cam").setSource(livekit.LivekitModels.TrackSource.CAMERA))
                .addTracks(livekit.LivekitModels.TrackInfo.newBuilder().setSid("TR_old").setSource(livekit.LivekitModels.TrackSource.MICROPHONE).setMuted(true))
                .build().toByteArray();

        gateway().muteMicrophone(room, bob);

        assertEquals(2, requests.size(), "one lookup and one mute: the camera and an already-muted microphone are left alone");
        assertTrue(requests.get(0).path().endsWith("/GetParticipant"), requests.get(0).path());
        assertTrue(requests.get(1).path().endsWith("/MutePublishedTrack"), requests.get(1).path());
        var body = LivekitRoom.MuteRoomTrackRequest.parseFrom(requests.get(1).body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(bob.toString(), body.getIdentity());
        assertEquals("TR_mic", body.getTrackSid());
        assertTrue(body.getMuted());
    }

    @Test
    void muteMicrophoneDoesNothingWhenTheyAreNotConnected() {
        status = 404;

        assertDoesNotThrow(() -> gateway().muteMicrophone(room, bob));

        assertEquals(1, requests.size(), "only the lookup: there is nothing to mute");
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

    @Test
    void broadcastRoomEventSendsOneDataMessageToTheWholeRoom() throws Exception {
        gateway().broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.HAND_RAISED, room, bob, Instant.now(), new StageState(List.of(bob), List.of()), null, null, null, null, null));

        Received request = onlyRequest();
        assertTrue(request.path().endsWith("/SendData"), request.path());
        var body = LivekitRoom.SendDataRequest.parseFrom(request.body());
        assertEquals(room.toString(), body.getRoom());
        assertEquals(List.of(), body.getDestinationIdentitiesList(), "no destination means everyone in the room");
        assertEquals("room-events", body.getTopic());
        String json = body.getData().toStringUtf8();
        assertTrue(json.contains("\"type\":\"HAND_RAISED\"") && json.contains(bob.toString()), json);
        assertTrue(json.contains("\"stage\":{\"speakers\":[\"" + bob + "\"],\"handRaiseQueue\":[]}"), "the new state travels in the event: " + json);
    }

    @Test
    void aRoomUpdateCarriesTheNewSettingsAndAMissingTopicAsNull() throws Exception {
        gateway().broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.ROOM_UPDATED, room, null, Instant.now(), null, null, null,
                new com.jtalk.livecommunication.domain.model.RoomSettings("New title", null, 6, alice), null, null));

        String json = LivekitRoom.SendDataRequest.parseFrom(onlyRequest().body()).getData().toStringUtf8();
        assertTrue(json.contains("\"type\":\"ROOM_UPDATED\""), json);
        assertTrue(json.contains("\"settings\":{\"title\":\"New title\",\"topic\":null,\"maxStageSeats\":6,\"hostId\":\"" + alice + "\"}"), "what the client reads: " + json);
    }

    @Test
    void aChatEventGoesOutOnItsOwnTopicCarryingTheCommentAndNoPersonalDetails() throws Exception {
        var comment = new com.jtalk.livecommunication.domain.model.RoomComment(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000c1"), bob,
                com.jtalk.livecommunication.domain.enums.CommentType.CORRECTION, "I goed home", "I went home", null, Instant.parse("2026-11-19T10:00:00Z"), null);

        gateway().broadcastChatEvent(com.jtalk.livecommunication.domain.model.ChatEvent.posted(room, Instant.parse("2026-11-19T10:00:00Z"), comment));

        var body = LivekitRoom.SendDataRequest.parseFrom(onlyRequest().body());
        assertEquals("room-chat", body.getTopic(), "apart from the room's state events, so a burst of comments never queues behind a stage change");
        assertEquals(List.of(), body.getDestinationIdentitiesList(), "everyone in the room");
        String json = body.getData().toStringUtf8();
        assertTrue(json.contains("\"type\":\"COMMENT_POSTED\""), json);
        assertTrue(json.contains("\"authorId\":\"" + bob + "\""), json);
        assertTrue(json.contains("\"correctedContent\":\"I went home\""), json);
        assertFalse(json.contains("replyTo"), "what is not there is not sent: " + json);
    }

    @Test
    void aFailedBroadcastNeverBreaksTheRequestThatCausedIt() {
        status = 400;
        contentType = "application/json";
        responseBody = "{\"code\":\"invalid_argument\",\"msg\":\"nope\"}".getBytes();

        assertDoesNotThrow(() -> gateway().broadcastRoomEvent(stageUpdated()));
        server.stop(0);
        assertDoesNotThrow(() -> gateway().broadcastRoomEvent(stageUpdated()));
    }

    private RoomRealtimeEvent stageUpdated() {
        return new RoomRealtimeEvent(RoomEventType.STAGE_UPDATED, room, null, Instant.now(), null, null, null, null, null, null);
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
    void aTokenLimitsWhatAModifiedClientCanDoWithIt() {
        Map<String, Object> speaker = JWT.decode(gateway().generateToken(room, alice, true)).getClaim("video").asMap();
        Map<String, Object> listener = JWT.decode(gateway().generateToken(room, bob, false)).getClaim("video").asMap();

        assertEquals(List.of("microphone"), speaker.get("canPublishSources"), "a speaker publishes a microphone, not video or a screen");
        assertEquals(false, speaker.get("canPublishData"), "nobody writes to the data channel, so nobody can fake a room event");
        assertEquals(false, listener.get("canPublishData"));
        assertEquals(false, speaker.get("canUpdateOwnMetadata"), "nobody can rewrite their own name or metadata in the room");
        assertEquals(false, listener.get("canUpdateOwnMetadata"));
        assertEquals(false, listener.get("canPublish"), "a listener who unmutes in a custom client is dropped by the media server");
    }

    @Test
    void aJoinTokenCarriesTheCachedPublicProfile() {
        var profile = new ParticipantPreview(alice, "Alice", "/avatars/preset-2.svg", "en", false);
        var token = JWT.decode(gateway().generateToken(room, alice, true, profile));

        assertEquals("Alice", token.getClaim("name").asString());
        assertTrue(token.getClaim("metadata").asString().contains("/avatars/preset-2.svg"));
        assertEquals(false, token.getClaim("video").asMap().get("canUpdateOwnMetadata"));
    }

}
