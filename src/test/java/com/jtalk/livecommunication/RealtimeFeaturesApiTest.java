package com.jtalk.livecommunication;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.PermissionChange;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.Removal;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.SubscriptionChange;
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

/** Grace leases, LiveKit webhooks, stage moderation, kick and low-bandwidth mode. */
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
        assertFalse(getParticipants(roomId).stream().anyMatch(p -> id("alice").toString().equals(p.get("userId"))),
                "a speaker whose lease ran out is no longer in the room");
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
        assertEquals(List.of(), getParticipants(roomId), "the host keeps the seat but is no longer in the room");
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
    void someoneWhoJoinedButNeverConnectedTheirMediaDropsOutWhileAConnectedOneStays() throws InterruptedException {
        String roomId = createRoom("host", "Presence", 3);
        join(roomId, "alice");
        join(roomId, "bob");
        webhook("participant_joined", roomId, "bob");
        assertEquals(2, getRoom(roomId).get("participantCount"));

        Thread.sleep(3_500);

        assertEquals(ids("bob"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
        assertEquals(1, getRoom(roomId).get("participantCount"), "the room's count ignores the one who never connected too");
    }

    @Test
    void aListenerWhoseMediaConnectionComesBackIsPresentAgainWithoutJoiningAgain() {
        String roomId = createRoom("host", "Back again", 3);
        join(roomId, "bob");
        webhook("participant_joined", roomId, "bob");

        webhook("participant_left", roomId, "bob");
        assertEquals(List.of(), getParticipants(roomId));

        webhook("participant_joined", roomId, "bob");
        assertEquals(ids("bob"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
        await("the net result to be announced", 5000, () -> gateway.presenceEvents.stream()
                .anyMatch(e -> e.presence().joined().contains(id("bob")) && !e.presence().left().contains(id("bob"))));
    }

    @Test
    void aConnectedConnectionIsNotForgottenWhenTheSameUserJoinsAgain() throws InterruptedException {
        String roomId = createRoom("host", "Rejoin", 3);
        join(roomId, "bob");
        webhook("participant_joined", roomId, "bob");

        join(roomId, "bob");
        Thread.sleep(3_500);

        assertEquals(ids("bob"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList());
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
                "{\"event\":\"participant_left\",\"room\":{\"name\":\"not-a-voice-room\"},\"participant\":{\"identity\":\"" + id("x") + "\"}}"));
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
    void stageEventsCarryTheNewStageSoClientsNeverRefetchIt() {
        String roomId = createRoom("host", "Carry state", 3);
        join(roomId, "host");
        join(roomId, "alice");
        join(roomId, "bob");

        postEmpty(roomId, "/stage/raise-hand", "bob");
        post(roomId, "/stage/join", "alice");

        var raised = gateway.events.get(0);
        var seated = gateway.events.get(1);
        assertEquals(List.of(id("host")), raised.stage().speakers());
        assertEquals(List.of(id("bob")), raised.stage().handRaiseQueue());
        assertEquals(List.of(id("host"), id("alice")), seated.stage().speakers());
        assertEquals(List.of(id("bob")), seated.stage().handRaiseQueue());
    }

    @Test
    void peopleArrivingAndLeavingAreAnnouncedInBatchesNotOneByOne() {
        String roomId = createRoom("host", "Crowd", 3);
        for (int i = 0; i < 6; i++) {
            join(roomId, "guest-" + i);
        }
        await("the arrivals to be announced", 5000, () -> arrivals(roomId).size() == 6);

        postEmpty(roomId, "/participants/leave", "guest-0");
        await("the departure to be announced", 5000, () -> gateway.presenceEvents.stream().anyMatch(e -> e.presence().left().contains(id("guest-0"))));

        assertTrue(gateway.presenceEvents.size() < 6, "six arrivals must not mean six events: " + gateway.presenceEvents);
        var last = gateway.presenceEvents.getLast().presence();
        assertEquals(5, last.participantCount());
        assertEquals(List.of(), gateway.events, "people coming and going never touch the stage events");
    }

    private java.util.Set<java.util.UUID> arrivals(String roomId) {
        return gateway.presenceEvents.stream().filter(e -> e.roomId().equals(uuid(roomId)))
                .flatMap(e -> e.presence().joined().stream()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void repeatedTapsOnTheHandButtonBroadcastOnlyTheRealChange() {
        String roomId = createRoom("host", "Tap tap", 3);
        join(roomId, "bob");

        for (int i = 0; i < 5; i++) {
            assertEquals(HttpStatus.ACCEPTED, postEmpty(roomId, "/stage/raise-hand", "bob").status());
        }
        delete(roomId, "/stage/raise-hand", "bob");
        delete(roomId, "/stage/raise-hand", "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        assertEquals(List.of("HAND_RAISED:" + id("bob"), "HAND_LOWERED:" + id("bob"), "HAND_RAISED:" + id("bob")),
                gateway.events.stream().map(e -> e.type() + ":" + e.targetUserId()).toList());
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
        webhook("participant_joined", roomId, "mallory");
        assertEquals(ids("host"), getParticipants(roomId).stream().map(p -> p.get("userId")).toList(),
                "a kicked client whose media connects again must not reappear in the room");
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
