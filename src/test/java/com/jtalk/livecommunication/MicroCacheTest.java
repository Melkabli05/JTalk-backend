package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.RedisCommandRecorder;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicroCacheTest extends ApiTestBase {

    @Inject
    RedisCommandRecorder redis;

    @Test
    void theSameDiscoveryPageIsLoadedOnceAndServedFromMemoryAfterThat() {
        createRoom("host-cache-a", Map.of("title", "cache-a", "targetLanguage", "mc", "nativeLanguage", "en"));
        listRooms("?targetLanguage=mc");

        List<String> again = redis.during(() -> listRooms("?targetLanguage=mc"));
        List<String> otherPage = redis.during(() -> listRooms("?targetLanguage=mc&size=5"));

        assertEquals(List.of(), again, "the second identical request must not touch Redis (or the database)");
        assertEquals(3, otherPage.size(), "a different page size is a different cache entry");
    }

    @Test
    void aBurstOfIdenticalRequestsRunsTheLoaderOnce() throws Exception {
        createRoom("host-cache-b", Map.of("title", "cache-b", "targetLanguage", "mb", "nativeLanguage", "en"));

        List<String> commands = redis.during(() -> {
            try (var pool = Executors.newFixedThreadPool(16)) {
                List<Callable<Object>> calls = java.util.stream.IntStream.range(0, 32)
                        .<Callable<Object>>mapToObj(i -> () -> listRooms("?targetLanguage=mb")).toList();
                for (var future : pool.invokeAll(calls)) {
                    future.get();
                }
                return null;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(3, commands.size(), "32 concurrent requests, one ZRANGE + ZCARD + ZCOUNT for the single room");
    }

    @Test
    void aNewRoomAppearsInTheFeedAtOnce() {
        createRoom("host-cache-c1", Map.of("title", "first", "targetLanguage", "mn", "nativeLanguage", "en"));
        assertEquals(1, listRooms("?targetLanguage=mn").size());

        createRoom("host-cache-c2", Map.of("title", "second", "targetLanguage", "mn", "nativeLanguage", "en"));

        assertEquals(2, listRooms("?targetLanguage=mn").size());
    }

    @Test
    void anEditedRoomShowsItsNewSettingsEverywhereAtOnce() {
        String roomId = createRoom("host-cache-d", "Before", 3);
        getRoom(roomId);
        listRooms("?targetLanguage=en");

        http.retrieve(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "After", "maxStageSeats", 2)).bearerAuth(token("host-cache-d")), MAP);

        assertEquals("After", getRoom(roomId).get("title"));
        assertTrue(listRooms("?targetLanguage=en&size=100").stream().anyMatch(r -> "After".equals(r.get("title"))));
        join(roomId, "guest-1");
        postEmpty(roomId, "/stage/promote/" + id("guest-1"), "host-cache-d");
        join(roomId, "guest-2");
        assertProblem(HttpStatus.CONFLICT, "stage-full", () -> postEmpty(roomId, "/stage/promote/" + id("guest-2"), "host-cache-d"));
    }

    @Test
    void aClosedRoomIsGoneImmediatelyEvenAfterItsMetadataWasCached() {
        String roomId = createRoom("host-cache-e", "Closing", 3);
        join(roomId, "alice");
        postEmpty(roomId, "/stage/raise-hand", "alice");

        delete(roomId, "", "host-cache-e");

        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> postEmpty(roomId, "/stage/promote/" + id("alice"), "host-cache-e"));
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> getRoom(roomId));
        assertTrue(listRooms("?targetLanguage=en&size=100").stream().noneMatch(r -> roomId.equals(r.get("id"))));
    }
}
