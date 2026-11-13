package com.jtalk.livecommunication;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.PermissionChange;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.Removal;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.Signal;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.SubscriptionChange;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.WhisperClose;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grace leases, LiveKit webhooks, stage moderation, whisper, kick and low-bandwidth mode. */
class RealtimeFeaturesApiTest extends ApiTestBase {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-test-secret-test-secret-0123";

    @Inject
    StatefulRedisConnection<String, String> redis;

    // ---- grace lease ------------------------------------------------------------------------------

    @Test
    void disconnectedSpeakerKeepsSeatUntilLeaseExpiresThenNextHandRaiserIsPromoted() {
        String roomId = createRoom("host", "Lease", 2);   // the host and one more speaker
        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        webhook("participant_left", roomId, "alice");

        // Within the lease: the seat is held and the participant list shows the reconnecting badge.
        assertTrue(speakers(roomId).contains(id("alice").toString()));
        assertEquals("RECONNECTING", statusOf(roomId, "alice"));

        // The lease expires (1s in tests) -> Redis keyspace event -> seat freed and bob promoted.
        await("bob promoted after alice's lease expired", 10_000, () -> speakers(roomId).contains(id("bob").toString()));
        assertEquals(new HashSet<>(ids("host", "bob")), speakers(roomId));
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), true)));
        assertTrue(gateway.events.stream().anyMatch(e -> e.type() == RoomEventType.STAGE_UPDATED), "the room is told the stage changed");
    }

    @Test
    void reconnectingWithinTheLeaseKeepsTheSeat() throws InterruptedException {
        String roomId = createRoom("host", "Reconnect", 3);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");

        webhook("participant_left", roomId, "alice");
        assertEquals("RECONNECTING", statusOf(roomId, "alice"));

        Map<String, Object> session = join(roomId, "alice");   // rejoining cancels the lease
        assertEquals(true, session.get("canPublishAudio"));
        Thread.sleep(2_000);   // longer than the lease would have lasted

        assertTrue(speakers(roomId).contains(id("alice").toString()));
        assertEquals("ACTIVE", statusOf(roomId, "alice"));
    }

    @Test
    void theHostsSeatIsNeverReclaimed() throws InterruptedException {
        String roomId = createRoom("host", "Host drops", 3);
        join(roomId, "host");

        webhook("participant_left", roomId, "host");
        Thread.sleep(2_000);

        assertTrue(speakers(roomId).contains(id("host").toString()));
        assertEquals(true, join(roomId, "host").get("canPublishAudio"), "the host comes back with publish rights");
    }

    @Test
    void listenerLeavingViaWebhookIsRemovedImmediately() {
        String roomId = createRoom("host", "Listener leaves", 3);
        join(roomId, "host");
        join(roomId, "bob");

        webhook("participant_left", roomId, "bob");

        assertEquals(ids("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
    }

    @Test
    void participantsWhoStopSendingHeartbeatsDropOutOfTheRoom() throws InterruptedException {
        String roomId = createRoom("host", "Presence", 3);
        join(roomId, "alice");
        join(roomId, "bob");
        assertEquals(2, getRoom(roomId).get("participantCount"));

        Thread.sleep(2_000);
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/participants/heartbeat", "bob").status());   // only bob keeps beating
        Thread.sleep(1_500);                                                                              // alice is now 3.5 s silent, bob 1.5 s

        assertEquals(ids("bob"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
        assertEquals(1, getRoom(roomId).get("participantCount"), "the room's count ignores the silent participant too");
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
    void webhooksForRoomsOrUsersThatAreNotOursAreIgnored() {
        assertEquals(HttpStatus.OK, sendWebhook(
                "{\"event\":\"participant_left\",\"room\":{\"name\":\"whisper-room\"},\"participant\":{\"identity\":\"" + id("x") + "\"}}"));
        assertEquals(HttpStatus.OK, sendWebhook(
                "{\"event\":\"participant_left\",\"room\":{\"name\":\"" + id("some-room") + "\"},\"participant\":{\"identity\":\"not-a-uuid\"}}"));
    }

    // ---- real-time events -------------------------------------------------------------------------

    @Test
    void uiStateChangesAreBroadcastToTheRoomSoClientsDoNotPoll() {
        String roomId = createRoom("host", "Events", 3);
        join(roomId, "host");
        join(roomId, "alice");
        join(roomId, "bob");

        postEmpty(roomId, "/stage/raise-hand", "bob");
        delete(roomId, "/stage/raise-hand", "bob");
        post(roomId, "/stage/join", "alice");
        webhook("participant_left", roomId, "alice");
        join(roomId, "alice");
        postEmpty(roomId, "/stage/leave", "alice");

        assertEquals(List.of(
                "HAND_RAISED:" + id("bob"), "HAND_LOWERED:" + id("bob"), "STAGE_UPDATED:null",
                "SPEAKER_RECONNECTING:" + id("alice"), "SPEAKER_RECONNECTED:" + id("alice"), "STAGE_UPDATED:null"),
                gateway.events.stream().map(e -> e.type() + ":" + e.targetUserId()).toList());
        assertTrue(gateway.events.stream().allMatch(e -> e.roomId().equals(uuid(roomId))));
    }

    @Test
    void aListenerLeavingChangesNothingOnTheStageSoNothingIsBroadcast() {
        String roomId = createRoom("host", "Quiet leave", 3);
        join(roomId, "host");
        join(roomId, "bob");

        postEmpty(roomId, "/participants/leave", "bob");

        assertEquals(List.of(), gateway.events);
    }

    // ---- stage ------------------------------------------------------------------------------------

    @Test
    void stageScriptsKeepWorkingAfterRedisForgetsThem() {
        String roomId = createRoom("host", "Script flush", 2);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");                   // runs the scripts by SHA
        redis.sync().scriptFlush();                     // what a Redis restart or failover does
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");  // NOSCRIPT -> falls back to sending the source
        assertEquals(ids("bob"), getStage(roomId).get("handRaiseQueue"));
        postEmpty(roomId, "/stage/leave", "alice");     // release-and-promote also recovers
        assertEquals(new HashSet<>(ids("host", "bob")), speakers(roomId));
    }

    @Test
    void concurrentSeatClaimsNeverOverfillTheStage() throws Exception {
        String roomId = createRoom("host", "Race", 2);   // the host plus exactly one free seat
        List<String> racers = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "racer" + i).toList();
        racers.forEach(racer -> join(roomId, racer));

        var startTogether = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(racers.size())) {
            List<java.util.concurrent.Future<Boolean>> claims = racers.stream().map(racer -> pool.submit(() -> {
                startTogether.await();
                try {
                    post(roomId, "/stage/join", racer);
                    return true;
                } catch (HttpClientResponseException e) {
                    assertEquals(HttpStatus.CONFLICT, e.getStatus());
                    return false;
                }
            })).toList();
            startTogether.countDown();
            long winners = 0;
            for (var claim : claims) {
                winners += claim.get() ? 1 : 0;
            }
            assertEquals(1, winners, "exactly one claimant gets the single free seat");
        }
        assertEquals(2, speakers(roomId).size(), "the stage holds the host and the winner, never more than its seats");
    }

    @Test
    void hostPromotesAndDemotes() {
        String roomId = createRoom("host", "Moderated stage", 3);
        join(roomId, "host");
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> postEmpty(roomId, "/stage/promote/" + id("bob"), "alice"));
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/promote/" + id("bob"), "host").status());
        assertEquals(new HashSet<>(ids("host", "bob")), speakers(roomId));
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"), "promoted users leave the queue");
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), true)));

        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/demote/" + id("bob"), "host").status());
        assertEquals(new HashSet<>(ids("host")), speakers(roomId));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), false)));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "host-cannot-leave-stage", () -> postEmpty(roomId, "/stage/demote/" + id("host"), "host"));
    }

    @Test
    void aFullStagePromotionIsRefusedAndAHandCanBeLowered() {
        String roomId = createRoom("host", "Full", 2);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        assertProblem(HttpStatus.CONFLICT, "stage-full", () -> postEmpty(roomId, "/stage/promote/" + id("bob"), "host"));
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/stage/raise-hand", "bob").status());
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
    }

    // ---- moderation -------------------------------------------------------------------------------

    @Test
    void hostKicksAListenerWhoCannotComeBack() {
        String roomId = createRoom("host", "Moderation", 3);
        join(roomId, "host");
        join(roomId, "mallory");
        postEmpty(roomId, "/stage/raise-hand", "mallory");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(roomId, "/participants/" + id("host"), "mallory"));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-kick-host", () -> delete(roomId, "/participants/" + id("host"), "host"));
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/participants/" + id("mallory"), "host").status());

        assertEquals(ids("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertEquals(List.of(new Removal(uuid(roomId), id("mallory"))), gateway.removals);
        assertProblem(HttpStatus.FORBIDDEN, "kicked-from-room", () -> join(roomId, "mallory"));
        assertProblem(HttpStatus.FORBIDDEN, "kicked-from-room", () -> postEmpty(roomId, "/participants/heartbeat", "mallory"));
        assertEquals(ids("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList(),
                "a kicked client that keeps sending heartbeats must not reappear in the room");
    }

    @Test
    void kickingASpeakerFreesTheirSeatForTheNextHandRaiser() {
        String roomId = createRoom("host", "Kick speaker", 2);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        delete(roomId, "/participants/" + id("alice"), "host");

        assertEquals(new HashSet<>(ids("host", "bob")), speakers(roomId));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), true)));
    }

    // ---- whisper ----------------------------------------------------------------------------------

    @Test
    void aWhisperGetsItsOwnPrivateRoomAndEachSideIsToldOnlyItsOwnToken() {
        String roomId = createRoom("host", "Whisper", 3);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        join(roomId, "carol");

        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", () -> whisper(roomId, "bob", "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "whisper-target-not-present", () -> whisper(roomId, "alice", "ghost"));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "whisper-to-self", () -> whisper(roomId, "alice", "alice"));

        assertEquals(HttpStatus.NO_CONTENT, whisper(roomId, "alice", "bob").status());

        assertEquals(2, gateway.signals.size());
        Signal toSpeaker = signalTo("alice");
        Signal toTarget = signalTo("bob");
        assertEquals("whisper", toSpeaker.topic());
        assertTrue(toSpeaker.payload().contains("\"action\":\"start\""), toSpeaker.payload());
        assertTrue(toSpeaker.payload().contains("whisper:" + id("alice") + ">" + id("bob") + ":" + id("alice") + ":publish=true"));
        assertTrue(toTarget.payload().contains("whisper:" + id("alice") + ">" + id("bob") + ":" + id("bob") + ":publish=false"));
        assertFalse(toTarget.payload().contains("publish=true"), "the speaker's token never goes to the target");
        assertFalse(toSpeaker.payload().contains("publish=false"), "the target's token never goes to the speaker");
        assertTrue(gateway.signals.stream().noneMatch(s -> s.destinations().contains(id("carol"))), "nobody else is told");

        gateway.signals.clear();
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "/stage/whisper", "alice").status());
        assertEquals(List.of(new WhisperClose(uuid(roomId), id("alice"), id("bob"))), gateway.closedWhispers);
        assertEquals(2, gateway.signals.size());
        assertTrue(gateway.signals.stream().allMatch(s -> s.payload().contains("\"action\":\"stop\"")));

        delete(roomId, "/stage/whisper", "alice");   // idempotent
        assertEquals(1, gateway.closedWhispers.size());
    }

    @Test
    void startingANewWhisperEndsThePreviousOne() {
        String roomId = createRoom("host", "Switch whisper", 3);
        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        join(roomId, "carol");

        whisper(roomId, "alice", "bob");
        whisper(roomId, "alice", "carol");

        assertEquals(List.of(new WhisperClose(uuid(roomId), id("alice"), id("bob"))), gateway.closedWhispers);
        assertTrue(gateway.signals.stream().anyMatch(s -> s.destinations().equals(List.of(id("carol"))) && s.payload().contains("\"action\":\"start\"")));
    }

    @Test
    void whisperCanBeSwitchedOffPerRoom() {
        String roomId = (String) createRoom("host", Map.of("title", "No whisper", "targetLanguage", "en", "nativeLanguage", "ar", "allowWhisper", false)).get("id");
        join(roomId, "host");
        join(roomId, "bob");

        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", () -> whisper(roomId, "host", "bob"));
    }

    // ---- low-bandwidth mode -----------------------------------------------------------------------

    @Test
    void lowBandwidthModePrunesSecondaryTracksAndOptimalRestoresThem() {
        String roomId = createRoom("host", "Network mode", 3);
        join(roomId, "bob");

        assertEquals(HttpStatus.NO_CONTENT, postJson(roomId, "/stage/network-mode", "bob", Map.of(
                "mode", "LOW_BANDWIDTH", "secondaryTrackSidsToDrop", List.of("TR_a", "TR_b"), "primaryTrackSidsToKeep", List.of("TR_main"))).status());
        assertEquals(List.of(
                new SubscriptionChange(uuid(roomId), id("bob"), List.of("TR_a", "TR_b"), false),
                new SubscriptionChange(uuid(roomId), id("bob"), List.of("TR_main"), true)), gateway.subscriptionChanges);

        gateway.subscriptionChanges.clear();
        postJson(roomId, "/stage/network-mode", "bob", Map.of("mode", "OPTIMAL"));
        assertEquals(1, gateway.subscriptionChanges.size());
        SubscriptionChange restore = gateway.subscriptionChanges.getFirst();
        assertTrue(restore.subscribe());
        assertEquals(List.of("TR_a", "TR_b"), restore.trackSids().stream().sorted().toList(), "the server remembered what it pruned");

        gateway.subscriptionChanges.clear();
        postJson(roomId, "/stage/network-mode", "bob", Map.of("mode", "OPTIMAL"));
        assertEquals(List.of(), gateway.subscriptionChanges, "nothing left to restore");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private Set<String> speakers(String roomId) {
        @SuppressWarnings("unchecked")
        List<String> speakers = (List<String>) getStage(roomId).get("speakers");
        return new HashSet<>(speakers);
    }

    private String statusOf(String roomId, String userName) {
        return getParticipants(roomId).stream()
                .filter(p -> id(userName).toString().equals(p.get("userId")))
                .map(p -> (String) p.get("status"))
                .findFirst().orElseThrow();
    }

    private io.micronaut.http.HttpResponse<?> whisper(String roomId, String speaker, String target) {
        return postJson(roomId, "/stage/whisper", speaker, Map.of("targetUserId", id(target).toString()));
    }

    private Signal signalTo(String userName) {
        return gateway.signals.stream().filter(s -> s.destinations().equals(List.of(id(userName)))).findFirst().orElseThrow();
    }

    private void webhook(String event, String roomId, String userName) {
        assertEquals(HttpStatus.OK, sendWebhook(
                "{\"event\":\"" + event + "\",\"room\":{\"name\":\"" + roomId + "\"},\"participant\":{\"identity\":\"" + id(userName) + "\"}}"));
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
