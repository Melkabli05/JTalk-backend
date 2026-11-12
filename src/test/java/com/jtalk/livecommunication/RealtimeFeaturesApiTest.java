package com.jtalk.livecommunication;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeMediaService;
import com.jtalk.livecommunication.support.FakeMediaService.PermissionChange;
import com.jtalk.livecommunication.support.FakeMediaService.SubscriptionChange;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Grace leases, LiveKit webhooks, whisper and adaptive subscription. */
class RealtimeFeaturesApiTest extends ApiTestBase {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-test-secret-test-secret-0123";

    @Inject
    StatefulRedisConnection<String, String> redis;

    // ---- grace lease ------------------------------------------------------------------------------

    @Test
    void disconnectedSpeakerKeepsSeatUntilLeaseExpiresThenNextHandRaiserIsPromoted() {
        String roomId = createRoom("host", "Lease", 1);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/stage/hand-raises", "bob");

        webhook("participant_left", roomId, "alice");

        // Within the lease: seat is held, and the participant list shows the reconnecting badge.
        assertEquals(List.of("alice"), getStage(roomId).get("speakers"));
        assertEquals("RECONNECTING", statusOf(roomId, "alice"));

        // Lease expires (1s in tests) -> Redis keyspace event -> seat freed and bob promoted.
        await("bob promoted after alice's lease expired", 10_000,
                () -> List.of("bob").equals(getStage(roomId).get("speakers")));
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertTrue(media.permissionChanges.contains(new PermissionChange(uuid(roomId), "bob", true)));
    }

    @Test
    void reconnectingWithinTheLeaseKeepsTheSeat() throws InterruptedException {
        String roomId = createRoom("host", "Reconnect", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");

        webhook("participant_left", roomId, "alice");
        assertEquals("RECONNECTING", statusOf(roomId, "alice"));

        // Rejoining (new token) cancels the lease.
        assertTrue(post(roomId, "/participants/me", "alice").get("mediaToken").toString().endsWith("publish=true"));
        Thread.sleep(2_000);   // longer than the lease would have lasted

        assertEquals(List.of("alice"), getStage(roomId).get("speakers"));
        assertEquals("ACTIVE", statusOf(roomId, "alice"));
    }

    @Test
    void listenerLeavingViaWebhookIsRemovedImmediately() {
        String roomId = createRoom("host", "Listener leaves", 2);
        post(roomId, "/participants/me", "host");
        post(roomId, "/participants/me", "bob");

        webhook("participant_left", roomId, "bob");

        assertEquals(List.of("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
    }

    @Test
    void webhookWithBadSignatureIsRejected() {
        var request = HttpRequest.POST("/webhooks/livekit", "{\"event\":\"participant_left\"}")
                .contentType("application/webhook+json")
                .header("Authorization", "not-a-valid-jwt");
        var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void webhookForNonVoiceRoomIsIgnored() {
        assertEquals(HttpStatus.OK, sendWebhook(
                "{\"event\":\"participant_left\",\"room\":{\"name\":\"some-other-room\"},\"participant\":{\"identity\":\"x\"}}"));
    }

    @Test
    void stageScriptsKeepWorkingAfterRedisForgetsThem() {
        String roomId = createRoom("host", "Script flush", 1);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");           // runs the scripts by SHA
        redis.sync().scriptFlush();                     // what a Redis restart or failover does
        post(roomId, "/participants/me", "bob");
        post(roomId, "/stage/hand-raises", "bob");      // NOSCRIPT -> falls back to sending the source
        assertEquals(List.of("bob"), getStage(roomId).get("handRaiseQueue"));
        delete(roomId, "/stage/speakers/alice", "alice");
        put(roomId, "/stage/speakers/bob", "host");
        assertEquals(List.of("bob"), getStage(roomId).get("speakers"));
    }

    // ---- moderation -------------------------------------------------------------------------------

    @Test
    void hostKicksAListenerWhoCannotComeBack() {
        String roomId = createRoom("host", "Moderation", 2);
        post(roomId, "/participants/me", "host");
        post(roomId, "/participants/me", "mallory");
        post(roomId, "/stage/hand-raises", "mallory");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(roomId, "/participants/host", "mallory"));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-kick-host", () -> delete(roomId, "/participants/host", "host"));
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/participants/mallory", "host").status());

        assertEquals(List.of("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertEquals(List.of(new FakeMediaService.Removal(uuid(roomId), "mallory")), media.removals);
        assertProblem(HttpStatus.FORBIDDEN, "kicked-from-room", () -> post(roomId, "/participants/me", "mallory"));
    }

    @Test
    void kickingASpeakerFreesTheirSeatForTheNextHandRaiser() {
        String roomId = createRoom("host", "Kick speaker", 1);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/stage/hand-raises", "bob");

        delete(roomId, "/participants/alice", "host");

        assertEquals(List.of("bob"), getStage(roomId).get("speakers"));
        assertTrue(media.permissionChanges.contains(new PermissionChange(uuid(roomId), "bob", true)));
    }

    // ---- whisper ----------------------------------------------------------------------------------

    @Test
    void aWhisperGetsItsOwnPrivateRoomAndOnlyTheTargetIsTold() {
        String roomId = createRoom("host", "Whisper", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/participants/me", "carol");

        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", () -> postJson(roomId, "/whisper", "bob", Map.of("targetUserId", "alice")));
        assertProblem(HttpStatus.NOT_FOUND, "whisper-target-not-present", () -> postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "ghost")));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "whisper-to-self", () -> postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "alice")));

        Map<String, Object> session = startWhisper(roomId, "alice", "bob");
        assertEquals("whisper:alice>bob:alice:publish=true", session.get("mediaToken"));   // the speaker's side, from the response

        assertEquals(1, media.signals.size());
        var start = media.signals.getFirst();
        assertEquals("whisper", start.topic());
        assertEquals(List.of("bob"), start.destinationIdentities(), "only the target is told; nobody else in the room");
        assertTrue(start.payload().contains("\"action\":\"start\""), start.payload());
        assertTrue(start.payload().contains("whisper:alice>bob:bob:publish=false"), "the target gets their own side");
        assertFalse(start.payload().contains("publish=true"), "the speaker's token never goes to the target");

        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/whisper", "alice").status());
        assertEquals(List.of(new FakeMediaService.WhisperClose(uuid(roomId), "alice", "bob")), media.closedWhispers);
        assertEquals(2, media.signals.size());
        assertTrue(media.signals.get(1).payload().contains("\"action\":\"stop\""));
        assertEquals(List.of("bob"), media.signals.get(1).destinationIdentities());

        delete(roomId, "/whisper", "alice");   // idempotent
        assertEquals(1, media.closedWhispers.size());
        assertEquals(2, media.signals.size());
    }

    @Test
    void startingANewWhisperEndsThePreviousOne() {
        String roomId = createRoom("host", "Switch whisper", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/participants/me", "carol");

        startWhisper(roomId, "alice", "bob");
        startWhisper(roomId, "alice", "carol");

        assertEquals(List.of(new FakeMediaService.WhisperClose(uuid(roomId), "alice", "bob")), media.closedWhispers);
        assertEquals(List.of(List.of("bob"), List.of("bob"), List.of("carol")),
                media.signals.stream().map(FakeMediaService.Signal::destinationIdentities).toList());   // start, stop, start
    }

    // ---- adaptive subscription --------------------------------------------------------------------

    @Test
    void strugglingListenerIsPrunedToThePrimaryTrackAndRestoredAfterRecovering() throws InterruptedException {
        String roomId = createRoom("host", "Quality", 2);
        post(roomId, "/participants/me", "bob");

        report(roomId, "bob", 20, 100, List.of("TR_host", "TR_alice", "TR_carol"), "TR_host");   // >15% loss
        assertEquals(List.of(new SubscriptionChange(uuid(roomId), "bob", List.of("TR_alice", "TR_carol"), false)),
                media.subscriptionChanges);

        media.subscriptionChanges.clear();
        report(roomId, "bob", 20, 100, List.of("TR_host"), "TR_host");   // nothing left to prune: no call to LiveKit
        report(roomId, "bob", 1, 100, List.of("TR_host"), "TR_host");    // healthy streak starts, not long enough yet
        assertEquals(List.of(), media.subscriptionChanges);
        Thread.sleep(1_200);                                             // recovery window is 1s in tests
        report(roomId, "bob", 1, 100, List.of("TR_host"), "TR_host");

        assertEquals(1, media.subscriptionChanges.size());
        SubscriptionChange restore = media.subscriptionChanges.getFirst();
        assertTrue(restore.subscribe());
        assertEquals(List.of("TR_alice", "TR_carol"), restore.trackSids().stream().sorted().toList());
    }

    @Test
    void lowDownstreamBandwidthAlsoCountsAsStruggling() {
        String roomId = createRoom("host", "Bandwidth", 2);
        post(roomId, "/participants/me", "bob");

        report(roomId, "bob", 0, 10, List.of("TR_a", "TR_b"), "TR_a");   // <25 kbps

        assertEquals(List.of(new SubscriptionChange(uuid(roomId), "bob", List.of("TR_b"), false)), media.subscriptionChanges);
    }

    @Test
    void aReportWithoutTracksNeverCallsLiveKit() {
        String roomId = createRoom("host", "No tracks", 2);
        post(roomId, "/participants/me", "bob");

        assertEquals(HttpStatus.NO_CONTENT, postJson(roomId, "/quality", "bob", Map.of("lossPercent", 30, "downstreamKbps", 100)).status());

        assertEquals(List.of(), media.subscriptionChanges);
    }

    @Test
    void invalidQualityReportIsRejected() {
        String roomId = createRoom("host", "Validation", 2);
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> postJson(roomId, "/quality", "bob", Map.of("lossPercent", 120, "downstreamKbps", 100)));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private Map<String, Object> startWhisper(String roomId, String speakerId, String targetId) {
        return http.retrieve(HttpRequest.POST(path(roomId, "/whisper"), Map.of("targetUserId", targetId)).bearerAuth(token(speakerId)), MAP);
    }

    private void report(String roomId, String userId, double lossPercent, int downstreamKbps, List<String> subscribed, String primary) {
        assertEquals(HttpStatus.NO_CONTENT, postJson(roomId, "/quality", userId, Map.of(
                "lossPercent", lossPercent, "downstreamKbps", downstreamKbps,
                "subscribedTrackSids", subscribed, "primaryTrackSid", primary)).status());
    }

    private String statusOf(String roomId, String userId) {
        return getParticipants(roomId).stream()
                .filter(p -> userId.equals(p.get("userId")))
                .map(p -> (String) p.get("status"))
                .findFirst().orElseThrow();
    }

    private void webhook(String event, String roomId, String identity) {
        assertEquals(HttpStatus.OK, sendWebhook(
                "{\"event\":\"" + event + "\",\"room\":{\"name\":\"" + roomId + "\"},\"participant\":{\"identity\":\"" + identity + "\"}}"));
    }

    /** Sends a webhook signed the way LiveKit signs it: JWT (HS256) carrying the SHA-256 of the body. */
    private HttpStatus sendWebhook(String body) {
        try {
            String hash = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            String jwt = JWT.create()
                    .withIssuer(API_KEY)
                    .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                    .withClaim("sha256", hash)
                    .sign(Algorithm.HMAC256(API_SECRET));
            return http.exchange(HttpRequest.POST("/webhooks/livekit", body)
                    .contentType("application/webhook+json")
                    .header("Authorization", jwt)).status();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
