package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeAuth0;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.sse.SseClient;
import io.micronaut.http.sse.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hallway's live view: it gets the open rooms once, then is told when rooms open, change and close and who is in them. */
class RoomFeedTest extends ApiTestBase {

    private static final String CHANNEL = "voiceroom.feed";

    @Inject
    @Client("/")
    SseClient sse;

    @Inject
    StatefulRedisConnection<String, String> redis;

    /** Opens the feed as this user; what arrives is collected in the returned watch. Dispose to disconnect. */
    private Watch watch(String userName) {
        return watch(userName, "/api/v1/voice-rooms/feed");
    }

    private Watch watch(String userName, String uri) {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        Disposable subscription = Flux.from(sse.eventStream(HttpRequest.GET(uri).bearerAuth(token(userName)), MAP))
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> room(Map<String, Object> message) {
        return (Map<String, Object>) message.get("room");
    }

    @Test
    void theFirstMessageIsTheOpenRoomsAsTheyAreNow() {
        String roomId = createRoom("host", "Already open", 3);
        Watch watch = watch("carol");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());

            assertEquals("SNAPSHOT", watch.received().get(0).get("type"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rooms = (List<Map<String, Object>>) watch.received().get(0).get("rooms");
            assertEquals(List.of(roomId), rooms.stream().map(r -> r.get("id")).toList());
            assertEquals("Already open", rooms.get(0).get("title"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void aRoomOpenedChangedAndClosedIsToldToEveryWatcher() {
        Watch watch = watch("carol");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());

            String roomId = createRoom("host", "Morning chat", 3);
            await("ROOM_OPENED", 5_000, () -> !watch.of("ROOM_OPENED").isEmpty());
            Map<String, Object> opened = watch.of("ROOM_OPENED").get(0);
            assertEquals(roomId, opened.get("roomId"));
            assertEquals("Morning chat", room(opened).get("title"));
            assertEquals(id("host").toString(), room(opened).get("hostId"));

            http.exchange(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "Evening chat")).bearerAuth(token("host")));
            await("ROOM_UPDATED", 5_000, () -> !watch.of("ROOM_UPDATED").isEmpty());
            assertEquals("Evening chat", room(watch.of("ROOM_UPDATED").get(0)).get("title"));

            http.exchange(HttpRequest.DELETE(path(roomId, "")).bearerAuth(token("host")));
            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
            assertEquals(roomId, watch.of("ROOM_CLOSED").get(0).get("roomId"));
            assertNull(watch.of("ROOM_CLOSED").get(0).get("room"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void theNumbersOnTheCardsFollowWhoJoinsAndWhoTakesTheStage() {
        String roomId = createRoom("host", "Numbers", 3);
        Watch watch = watch("carol");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());

            join(roomId, "alice");
            await("a head count of 1", 10_000, () -> counts(watch, roomId).contains(1));

            post(roomId, "/stage/join", "alice");
            await("alice on stage", 10_000, () -> watch.of("ROOM_STATS").stream().anyMatch(m -> speakers(m).contains(id("alice").toString())));
        } finally {
            watch.subscription().dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> speakers(Map<String, Object> stats) {
        return (List<String>) ((Map<String, Object>) stats.get("live")).get("speakers");
    }

    private static List<Integer> counts(Watch watch, String roomId) {
        return watch.of("ROOM_STATS").stream()
                .filter(m -> roomId.equals(m.get("roomId")))
                .map(m -> ((Number) ((Map<?, ?>) m.get("live")).get("participantCount")).intValue())
                .toList();
    }

    @Test
    void whatAnotherInstanceSaysReachesTheWatchersOfThisOne() {
        Watch watch = watch("carol");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());

            // Another instance publishes to the same channel; this one has no part in it but to forward it.
            String roomId = UUID.randomUUID().toString();
            long receivers = redis.sync().publish(CHANNEL, "{\"type\":\"ROOM_CLOSED\",\"roomId\":\"" + roomId + "\"}");

            assertTrue(receivers >= 1, "this instance listens");
            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
            assertEquals(roomId, watch.of("ROOM_CLOSED").get(0).get("roomId"));
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void aMessageThatCannotBeReadDoesNotStopTheFeed() {
        Watch watch = watch("carol");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());

            redis.sync().publish(CHANNEL, "not json");
            redis.sync().publish(CHANNEL, "{\"type\":\"ROOM_CLOSED\",\"roomId\":\"" + id("x") + "\"}");

            await("ROOM_CLOSED", 5_000, () -> !watch.of("ROOM_CLOSED").isEmpty());
        } finally {
            watch.subscription().dispose();
        }
    }

    @Test
    void targetLanguageFiltersSnapshotAndLiveEvents() {
        String spanishExisting = (String) createRoom("host-es-1", Map.of("title", "Spanish 1", "targetLanguage", "es", "nativeLanguage", "en")).get("id");
        createRoom("host-fr-1", Map.of("title", "French 1", "targetLanguage", "fr", "nativeLanguage", "en"));
        Watch watch = watch("carol", "/api/v1/voice-rooms/feed?targetLanguage=es");
        try {
            await("SNAPSHOT", 5_000, () -> !watch.of("SNAPSHOT").isEmpty());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapshotRooms = (List<Map<String, Object>>) watch.of("SNAPSHOT").get(0).get("rooms");
            assertEquals(List.of(spanishExisting), snapshotRooms.stream().map(r -> r.get("id")).toList());

            createRoom("host-fr-2", Map.of("title", "French 2", "targetLanguage", "fr", "nativeLanguage", "en"));
            String spanishNew = (String) createRoom("host-es-2", Map.of("title", "Spanish 2", "targetLanguage", "es", "nativeLanguage", "en")).get("id");
            await("ROOM_OPENED", 5_000, () -> !watch.of("ROOM_OPENED").isEmpty());
            assertEquals(List.of(spanishNew), watch.of("ROOM_OPENED").stream().map(m -> m.get("roomId")).toList());
        } finally {
            watch.subscription().dispose();
        }
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
