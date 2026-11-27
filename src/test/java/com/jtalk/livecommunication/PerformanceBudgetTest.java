package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.RedisCommandRecorder;
import com.jtalk.livecommunication.support.RedisCommandRecorder.Trace;
import com.jtalk.livecommunication.support.SqlStatementCounter;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hot paths by counting what a request really costs: Redis round trips (what a request waits on over a network; commands sent
 * together share one) and SQL statements. A regression, such as a read that waits for the one before it or a query per webhook, fails
 * here instead of showing up as latency under load. The scheduled batches are switched off so only the request's own work is counted.
 */
class PerformanceBudgetTest extends ApiTestBase {

    @Inject
    RedisCommandRecorder redisCommands;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        properties.put("voice-room.presence-batch", "1h");
        properties.put("voice-room.reaction-batch", "1h");
        return properties;
    }

    private record Cost(Trace redis, int sql) {
    }

    private Cost cost(Runnable request) {
        int[] sql = {0};
        Trace redis = redisCommands.trace(() -> sql[0] = SqlStatementCounter.during(() -> {
            request.run();
            return null;
        }));
        return new Cost(redis, sql[0]);
    }

    private String roomWithListeners(int listeners) {
        String room = createRoom("host", "Budget", 6);
        join(room, "host");
        for (int i = 0; i < listeners; i++) {
            join(room, "listener-" + i);
        }
        getRoom(room);   // the room is cached, as it is for a room people are already in
        return room;
    }

    @Test
    void theStageIsReadInOneRoundTrip() {
        String room = roomWithListeners(3);

        Cost stage = cost(() -> getStage(room));

        assertEquals(1, stage.redis().roundTrips(), "the speakers and the hand-raise queue are read together: " + stage.redis());
        assertEquals(2, stage.redis().commands().size());
        assertEquals(0, stage.sql());
    }

    @Test
    void theParticipantListIsTwoRoundTripsHoweverManyPeopleAreInTheRoom() {
        String room = roomWithListeners(3);
        Cost few = cost(() -> getParticipants(room));
        for (int i = 3; i < 25; i++) {
            join(room, "listener-" + i);
        }

        Cost many = cost(() -> getParticipants(room));

        assertEquals(2, few.redis().roundTrips(), "stage and presence together, then the leases of exactly those people: " + few.redis());
        assertEquals(few.redis().commands(), many.redis().commands(), "no lookup per person: the leases are one MGET for everyone");
        assertEquals(2, many.redis().roundTrips());
        assertEquals(0, many.sql());
        assertEquals(26, getParticipants(room).size());
    }

    @Test
    void anActionOnTheStageCostsTheActionAndOneRead() {
        String room = roomWithListeners(4);
        postEmpty(room, "/stage/moderator/" + id("listener-0"), "host");
        postEmpty(room, "/stage/moderator/" + id("listener-3"), "host");
        post(room, "/stage/join", "listener-0");           // the first call of a Lua script also sends the script: warm each one up
        postEmpty(room, "/stage/raise-hand", "listener-1");
        postEmpty(room, "/stage/leave", "listener-0");
        post(room, "/stage/join", "listener-0");

        Cost raiseHand = cost(() -> postEmpty(room, "/stage/raise-hand", "listener-2"));
        Cost takeSeat = cost(() -> post(room, "/stage/join", "listener-3"));
        Cost leaveSeat = cost(() -> postEmpty(room, "/stage/leave", "listener-3"));

        assertEquals(3, raiseHand.redis().roundTrips(), "are they in the room, the hand goes up, then the stage is read once to tell the room: " + raiseHand.redis());
        assertTrue(takeSeat.redis().roundTrips() <= 6, "are they in the room, moderator check, claim the seat, clear stale mutes, read the stage to tell the room, read the room's numbers: " + takeSeat.redis());
        assertTrue(leaveSeat.redis().roundTrips() <= 3, leaveSeat.redis().toString());
        assertEquals(0, raiseHand.sql() + takeSeat.sql() + leaveSeat.sql(), "the room is cached");
    }

    @Test
    void joiningARoomBatchesBoundedStateAndProfilesWithoutAQuery() {
        String room = roomWithListeners(3);

        Cost listener = cost(() -> join(room, "newcomer"));
        Cost host = cost(() -> join(room, "host"));

        assertEquals(2, listener.redis().roundTrips(), listener.redis().toString());
        assertEquals(3, host.redis().roundTrips(), host.redis().toString());
        assertEquals(1, listener.redis().count("EVALSHA"), "one script both registers presence and reads the snapshot");
        assertEquals(0, listener.redis().count("SMEMBERS"));
        assertTrue(listener.redis().count("HMGET") <= 33);
        assertEquals(0, listener.sql() + host.sql());
    }

    @Test
    void leavingARoomIsOneRoundTrip() {
        String room = roomWithListeners(3);

        Cost leave = cost(() -> postEmpty(room, "/participants/leave", "listener-0"));

        assertEquals(1, leave.redis().roundTrips(), "presence, hand and seat in one go: " + leave.redis());
        assertEquals(0, leave.sql());
    }

    @Test
    void openingARoomReadsNothingBackFromRedis() {
        Cost open = cost(() -> createRoom("fresh-host", "Fresh", 4));

        assertEquals(2, open.sql(), "look for the host's open room, then save: " + open);
        assertTrue(open.redis().roundTrips() <= 3, "the stage and solo timer are set up without reads (the rest is the hallways being told): " + open.redis());
    }

    @Test
    void networkModeAndKickingShareTheirRedisCommandsInRoundTrips() {
        String room = roomWithListeners(3);

        Cost network = cost(() -> postJson(room, "/stage/network-mode", "listener-0", Map.of("mode", "LOW_BANDWIDTH",
                "secondaryTrackSidsToDrop", java.util.List.of("TR_a"), "primaryTrackSidsToKeep", java.util.List.of("TR_b"))));
        Cost kick = cost(() -> delete(room, "/participants/" + id("listener-1"), "host"));

        assertEquals(2, network.redis().roundTrips(), "are they in the room, then the four commands together: " + network.redis());
        assertTrue(kick.redis().roundTrips() <= 4, "ban in one, leaving in one, then telling the room: " + kick.redis());
    }

    @Test
    void closingARoomDoesNotMakeTheHostWaitForTheMediaServer() throws Exception {
        String room = roomWithListeners(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        gateway.deleteRoomGate = gate;

        long started = System.nanoTime();
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "", "host").status());
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(gateway.closedRooms.isEmpty(), "the media server has not answered yet, and the host already has their answer");
        assertTrue(tookMillis < 2_000, "the answer waited " + tookMillis + " ms");
        gate.countDown();
        await("the media room is deleted afterwards", 5_000, () -> gateway.closedRooms.contains(uuid(room)));
    }

    @Test
    void commentingAndReadingTheChatAreOneRoundTripEach() {
        String room = roomWithListeners(2);
        postJson(room, "/comments", "listener-0", Map.of("type", "TEXT", "content", "warm up the script"));   // the first call also sends the script

        Cost post = cost(() -> postJson(room, "/comments", "listener-1", Map.of("type", "TEXT", "content", "hello")));
        Cost read = cost(() -> http.retrieve(HttpRequest.GET("/api/v1/voice-rooms/" + room + "/comments").bearerAuth(token("listener-1")), MAP));

        assertEquals(1, post.redis().roundTrips(), "one script: in the room? muted? too fast? then keep it: " + post.redis());
        assertEquals(1, post.redis().commands().size());
        assertEquals(1, read.redis().roundTrips(), "in the room, the comments, the topic and whether they are muted, together: " + read.redis());
        assertEquals(0, post.sql() + read.sql(), "chat never touches the database");
    }

    @Test
    void liveKitWebhooksNeverTouchTheDatabaseOnceTheRoomIsKnown() {
        String room = roomWithListeners(2);

        Cost joined = cost(() -> webhook("participant_joined", room, "listener-0"));
        Cost left = cost(() -> webhook("participant_left", room, "listener-1"));

        assertEquals(0, joined.sql(), "the room comes from the cache, not a query per webhook");
        assertEquals(0, left.sql());
    }

    @Test
    void whenARoomClosesTheBurstOfWebhooksCostsOneQueryNotOnePerParticipant() {
        String room = roomWithListeners(10);
        delete(room, "", "host");   // closes the room and evicts it from the cache

        Cost burst = cost(() -> {
            for (int i = 0; i < 10; i++) {
                webhook("participant_left", room, "listener-" + i);
            }
        });

        assertTrue(burst.sql() <= 1, "ten webhooks for a room that is gone ran " + burst.sql() + " queries; \"no such room\" is remembered too");
    }

    @Test
    void aRoomThatWasEditedIsSeenAsEditedAtOnce() {
        String room = roomWithListeners(1);
        assertEquals("Budget", getRoom(room).get("title"));

        http.exchange(HttpRequest.PATCH("/api/v1/voice-rooms/" + room, Map.of("title", "Renamed")).bearerAuth(token("host")), MAP);

        assertEquals("Renamed", getRoom(room).get("title"), "the cache is evicted on a write, not left to expire");
    }

    private void webhook(String event, String roomId, String user) {
        String body = "{\"event\":\"" + event + "\",\"room\":{\"name\":\"" + roomId + "\"},\"participant\":{\"identity\":\"" + id(user) + "\"}}";
        try {
            String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            String jwt = com.auth0.jwt.JWT.create().withIssuer("test-key").withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                    .withClaim("sha256", hash).sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret-test-secret-test-secret-0123"));
            assertEquals(HttpStatus.OK, http.exchange(HttpRequest.POST("/webhooks/livekit", body)
                    .contentType("application/webhook+json").header("Authorization", jwt)).status());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
