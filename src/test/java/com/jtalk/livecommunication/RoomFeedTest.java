package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeAuth0;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.sse.Event;
import io.micronaut.http.client.sse.SseClient;
import io.micronaut.http.client.annotation.Client;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hallway's live view: it is told when rooms open, change and close, and who is in them, so it never has to ask again. */
class RoomFeedTest extends ApiTestBase {

    @Inject
    @Client("/")
    SseClient sse;

    @Inject
    StatefulRedisConnection<String, String> redis;

    @Inject
    com.jtalk.livecommunication.support.RedisCommandRecorder recorder;

    private static final String CHANNEL = "voiceroom:feed";

    /** Opens the feed as this user; what arrives is collected in the returned list. Dispose to disconnect. */
    private Watch watch(String userName) {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        Disposable subscription = Flux.from(sse.eventStream(HttpRequest.GET("/api/v1/voice-rooms/feed").bearerAuth(token(userName)), MAP))
                .map(Event::getData)
                .subscribe(received::add);
        return new Watch(received, subscription);
    }

    private record Watch(List<Map<String, Object>> received, Disposable subscription) {

        /** The messages of this type, oldest first. */
        List<Map<String, Object>> of(String type) {
            return received.stream().filter(m -> type.equals(m.get("type"))).toList();
        }
    }

    @Test
    void itSaysReadyFirst() {
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());
            assertEquals("READY", watch.received().get(0).get("type"));
            assertNull(watch.received().get(0).get("roomId"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void aRoomOpenedChangedAndClosedIsToldToEveryWatcher() {
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());

            String roomId = createRoom("host", "Morning chat", 3);
            await("ROOM_OPENED", 5_000, () -> !watch.of("ROOM_OPENED").isEmpty());
            Map<String, Object> opened = watch.of("ROOM_OPENED").get(0);
            assertEquals(roomId, opened.get("roomId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> room = (Map<String, Object>) opened.get("room");
            assertEquals("Morning chat", room.get("title"));
            assertEquals(id("host").toString(), room.get("hostId"));

            http.exchange(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "Evening chat")).bearerAuth(token("host")));
            await("ROOM_UPDATED", 5_000, () -> !watch.of("ROOM_UPDATED").isEmpty());
            @SuppressWarnings("unchecked")
            Map<String, Object> updated = (Map<String, Object>) watch.of("ROOM_UPDATED").get(0).get("room");
            assertEquals("Evening chat", updated.get("title"));

            http.exchange(HttpRequest.DELETE(path(roomId, "")).bearerAuth(token("host")));
            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
            assertEquals(roomId, watch.of("ROOM_CLOSED").get(0).get("roomId"));
            assertNull(watch.of("ROOM_CLOSED").get(0).get("room"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void theNumbersOnTheCardsFollowWhoJoins() {
        String roomId = createRoom("host", "Numbers", 3);
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());

            join(roomId, "alice");
            await("ROOM_STATS", 10_000, () -> !watch.of("ROOM_STATS").isEmpty());
            Map<String, Object> stats = watch.of("ROOM_STATS").get(watch.of("ROOM_STATS").size() - 1);
            assertEquals(roomId, stats.get("roomId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> live = (Map<String, Object>) stats.get("live");
            assertTrue(live.containsKey("speakers"));
            assertTrue(live.containsKey("participantCount"));
            assertTrue(live.containsKey("handRaiseCount"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void openingARoomWhileNobodyWatchesCostsNothingAndLaterWatchersLoadTheList() {
        createRoom("host", "Before anyone watched", 3);
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());
            assertTrue(watch.of("ROOM_OPENED").isEmpty(), "what happened before connecting comes from the list, not the feed");
            assertEquals(1, listRooms("?size=100").size());
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void whatAnotherInstanceSaysReachesTheWatchersOfThisOne() {
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());

            // Another instance publishes to the same channel; this one has no part in it but to forward it.
            String roomId = java.util.UUID.randomUUID().toString();
            long receivers = redis.sync().publish(CHANNEL, "{\"type\":\"ROOM_CLOSED\",\"roomId\":\"" + roomId + "\"}");

            assertEquals(1, receivers, "this instance is subscribed while a hallway watches");
            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
            assertEquals(roomId, watch.of("ROOM_CLOSED").get(0).get("roomId"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void aMessageThatCannotBeReadIsIgnoredAndTheFeedCarriesOn() {
        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());

            redis.sync().publish(CHANNEL, "not json");
            redis.sync().publish(CHANNEL, "{\"type\":\"ROOM_CLOSED\",\"roomId\":\"" + id("x") + "\"}");

            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void anInstanceListensOnlyWhileAHallwayIsWatching() {
        assertEquals(0, redis.sync().publish(CHANNEL, "{}"), "nobody watching: not subscribed, nothing is delivered here");

        Watch watch = watch("carol");
        await("READY", 5_000, () -> !watch.of("READY").isEmpty());
        assertEquals(1, redis.sync().publish(CHANNEL, "{}"));

        watch.subscription().dispose();
        await("unsubscribed", 5_000, () -> redis.sync().publish(CHANNEL, "{}") == 0);
    }

    @Test
    void theNumbersOnTheCardsAreOnlyReadWhileSomeoneWatches() {
        String roomId = createRoom("host", "Quiet", 3);
        await("earlier tests' hallways gone", 10_000, () -> redis.sync().zcard("voiceroom:feed:watchers") == 0);

        long withoutWatchers = stageReads(() -> join(roomId, "alice"));

        Watch watch = watch("carol");
        try {
            await("READY", 5_000, () -> !watch.of("READY").isEmpty());
            long withWatchers = stageReads(() -> join(roomId, "bob"));

            assertTrue(withWatchers > withoutWatchers, "a watched hallway is told the numbers (" + withWatchers + " vs " + withoutWatchers + " reads)");
        } finally {
            watch.subscription().dispose();
        }
    }

    /** How many stage reads (ZRANGE) Redis saw while the action ran and a batch of the numbers had time to be sent. */
    private long stageReads(Runnable action) {
        return recorder.during(() -> {
            action.run();
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).stream().filter("ZRANGE"::equals).count();
    }

    @Test
    void itNeedsAValidToken() {
        var ex = assertThrows(HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.GET("/api/v1/voice-rooms/feed").accept("text/event-stream")
                        .bearerAuth(FakeAuth0.signWithAnotherKey(FakeAuth0.baseToken("auth0|" + id("carol"))
                                .withClaim(FakeAuth0.USER_ID_CLAIM, id("carol").toString())))));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }
}
