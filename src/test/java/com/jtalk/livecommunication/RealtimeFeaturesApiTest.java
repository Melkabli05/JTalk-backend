package com.jtalk.livecommunication;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.livecommunication.domain.service.MediaService.MediaTrack;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeMediaService.PermissionChange;
import com.jtalk.livecommunication.support.FakeMediaService.SubscriptionChange;
import io.lettuce.core.Range;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Grace leases, LiveKit webhooks, whisper, adaptive subscription and the audience mix trigger. */
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

    // ---- whisper ----------------------------------------------------------------------------------

    @Test
    void speakersCanWhisperToAPresentParticipant() {
        String roomId = createRoom("host", "Whisper", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");

        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", () -> postJson(roomId, "/whisper", "bob", Map.of("targetUserId", "alice")));
        assertProblem(HttpStatus.NOT_FOUND, "whisper-target-not-present", () -> postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "ghost")));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "whisper-to-self", () -> postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "alice")));

        assertEquals(HttpStatus.NO_CONTENT, postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "bob")).status());
        assertEquals(1, media.signals.size());
        var start = media.signals.getFirst();
        assertEquals("whisper", start.topic());
        assertEquals(List.of("alice", "bob"), start.destinationIdentities());
        assertTrue(start.payload().contains("\"action\":\"start\""), start.payload());
        assertTrue(start.payload().contains("whisper_target_bob"), start.payload());

        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/whisper", "alice").status());
        assertEquals(2, media.signals.size());
        assertTrue(media.signals.get(1).payload().contains("\"action\":\"stop\""));
        delete(roomId, "/whisper", "alice");   // idempotent: no third signal
        assertEquals(2, media.signals.size());
    }

    @Test
    void whisperTrackIsHiddenFromEveryoneExceptSpeakerAndTarget() {
        String roomId = createRoom("host", "Private whisper", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/participants/me", "carol");
        postJson(roomId, "/whisper", "alice", Map.of("targetUserId", "bob"));

        assertEquals(HttpStatus.OK, sendWebhook("{\"event\":\"track_published\",\"room\":{\"name\":\"" + roomId
                + "\"},\"participant\":{\"identity\":\"alice\"},\"track\":{\"sid\":\"TR_whisper\",\"name\":\"whisper_target_bob\"}}"));

        List<String> unsubscribed = media.subscriptionChanges.stream()
                .filter(change -> !change.subscribe() && change.trackSids().equals(List.of("TR_whisper")))
                .map(SubscriptionChange::userId).sorted().toList();
        assertEquals(List.of("carol", "host"), unsubscribed);   // not alice (speaker) and not bob (target)
    }

    @Test
    void whisperTrackWithoutAMatchingSessionIsIgnored() {
        String roomId = createRoom("host", "Rogue track", 2);
        post(roomId, "/participants/me", "host");
        post(roomId, "/participants/me", "bob");

        sendWebhook("{\"event\":\"track_published\",\"room\":{\"name\":\"" + roomId
                + "\"},\"participant\":{\"identity\":\"host\"},\"track\":{\"sid\":\"TR_x\",\"name\":\"whisper_target_bob\"}}");

        assertEquals(List.of(), media.subscriptionChanges);
    }

    // ---- adaptive subscription --------------------------------------------------------------------

    @Test
    void strugglingListenerIsMovedToTheMixedTrackAndBackAfterRecovering() throws InterruptedException {
        String roomId = createRoom("host", "Quality", 2);
        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        media.tracks = List.of(
                new MediaTrack("host", "TR_host", "mic"),
                new MediaTrack("alice", "TR_alice", "mic"),
                new MediaTrack("bob", "TR_bob", "mic"),
                new MediaTrack("mixer-1", "TR_mix", "mcu_mono_fallback"));

        report(roomId, "bob", 20, 100);   // >15% loss

        assertTrue(media.subscriptionChanges.contains(
                new SubscriptionChange(uuid(roomId), "bob", List.of("TR_host", "TR_alice"), false)));
        assertTrue(media.subscriptionChanges.contains(
                new SubscriptionChange(uuid(roomId), "bob", List.of("TR_mix"), true)));
        assertEquals(List.of("START FALLBACK"), mixerEvents(roomId, "FALLBACK"));

        media.subscriptionChanges.clear();
        report(roomId, "bob", 1, 100);    // healthy streak starts, not long enough yet
        assertEquals(List.of(), media.subscriptionChanges);
        Thread.sleep(1_200);              // recovery window is 1s in tests
        report(roomId, "bob", 1, 100);

        assertTrue(media.subscriptionChanges.contains(
                new SubscriptionChange(uuid(roomId), "bob", List.of("TR_host", "TR_alice"), true)));
        assertTrue(media.subscriptionChanges.contains(
                new SubscriptionChange(uuid(roomId), "bob", List.of("TR_mix"), false)));
        assertEquals(List.of("START FALLBACK", "STOP FALLBACK"), mixerEvents(roomId, "FALLBACK"));
    }

    @Test
    void lowDownstreamBandwidthAlsoCountsAsStruggling() {
        String roomId = createRoom("host", "Bandwidth", 2);
        post(roomId, "/participants/me", "bob");
        media.tracks = List.of(new MediaTrack("host", "TR_host", "mic"), new MediaTrack("mixer-1", "TR_mix", "mcu_mono_fallback"));

        report(roomId, "bob", 0, 10);     // <25 kbps

        assertTrue(media.subscriptionChanges.contains(new SubscriptionChange(uuid(roomId), "bob", List.of("TR_mix"), true)));
    }

    @Test
    void listenerStaysOnSpeakerTracksUntilTheMixedTrackExists() {
        String roomId = createRoom("host", "No mixer yet", 2);
        post(roomId, "/participants/me", "bob");
        media.tracks = List.of(new MediaTrack("host", "TR_host", "mic"));

        report(roomId, "bob", 30, 100);

        assertEquals(List.of(), media.subscriptionChanges);          // never silenced
        assertEquals(List.of("START FALLBACK"), mixerEvents(roomId, "FALLBACK"));   // but the mixer is requested
    }

    @Test
    void invalidQualityReportIsRejected() {
        String roomId = createRoom("host", "Validation", 2);
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> postJson(roomId, "/quality", "bob", Map.of("lossPercent", 120, "downstreamKbps", 100)));
    }

    // ---- audience broadcast -----------------------------------------------------------------------

    @Test
    void growingPastTheThresholdStartsTheBroadcastMixAndShrinkingStopsIt() {
        String roomId = createRoom("host", "Audience", 2);   // threshold is 2 in tests

        post(roomId, "/participants/me", "host");
        assertEquals(List.of(), mixerEvents(roomId, "BROADCAST"));
        post(roomId, "/participants/me", "alice");
        assertEquals(List.of("START BROADCAST"), mixerEvents(roomId, "BROADCAST"));
        post(roomId, "/participants/me", "bob");              // still above: no duplicate START
        assertEquals(List.of("START BROADCAST"), mixerEvents(roomId, "BROADCAST"));

        delete(roomId, "/participants/me", "bob");
        delete(roomId, "/participants/me", "alice");          // 1 present < 80% of threshold
        assertEquals(List.of("START BROADCAST", "STOP BROADCAST"), mixerEvents(roomId, "BROADCAST"));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void report(String roomId, String userId, double lossPercent, int downstreamKbps) {
        assertEquals(HttpStatus.NO_CONTENT, postJson(roomId, "/quality", userId,
                Map.of("lossPercent", lossPercent, "downstreamKbps", downstreamKbps)).status());
    }

    private String statusOf(String roomId, String userId) {
        return getParticipants(roomId).stream()
                .filter(p -> userId.equals(p.get("userId")))
                .map(p -> (String) p.get("status"))
                .findFirst().orElseThrow();
    }

    /** Mixer assignments written to the Redis Stream for this room and mode, as "ACTION MODE", oldest first. */
    private List<String> mixerEvents(String roomId, String mode) {
        return redis.sync().xrange("mixer:assignments", Range.create("-", "+")).stream()
                .map(message -> message.getBody())
                .filter(body -> roomId.equals(body.get("roomId")) && mode.equals(body.get("mode")))
                .map(body -> body.get("action") + " " + body.get("mode"))
                .toList();
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
