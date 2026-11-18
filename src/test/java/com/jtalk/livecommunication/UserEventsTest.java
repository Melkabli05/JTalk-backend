package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the user service announces: an account that is deleted takes the rooms it was running with it. */
class UserEventsTest extends ApiTestBase {

    private static final String CHANNEL = "user-events";

    @Inject
    StatefulRedisConnection<String, String> redis;

    private void announce(String json) {
        redis.sync().publish(CHANNEL, json);
    }

    private void deleted(UUID userId) {
        announce("{\"type\":\"USER_DELETED\",\"userId\":\"%s\"}".formatted(userId));
    }

    private List<Object> openRoomIds() {
        return listRooms("").stream().map(room -> room.get("id")).toList();
    }

    @Test
    void theRoomsOfADeletedAccountAreClosedAndTheirMediaRoomsRemoved() {
        String roomId = createRoom("alice", "Spanish coffee", 3);
        join(roomId, "bob");
        assertTrue(openRoomIds().contains(roomId));

        deleted(id("alice"));

        await("the deleted host's room closes", 10_000, () -> !openRoomIds().contains(roomId));
        await("its media room is removed", 10_000, () -> gateway.closedRooms.contains(uuid(roomId)));
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> getRoom(roomId));
    }

    @Test
    void otherPeoplesRoomsAreNotTouched() {
        String alices = createRoom("alice", "Alice's room", 3);
        String carols = createRoom("carol", "Carol's room", 3);

        deleted(id("alice"));

        await("alice's room closes", 10_000, () -> !openRoomIds().contains(alices));
        assertTrue(openRoomIds().contains(carols));
        assertEquals(carols, getRoom(carols).get("id"));
    }

    @Test
    void aProfileChangeIsHeardAndIgnored() {
        String roomId = createRoom("alice", "Still open", 3);

        announce("{\"type\":\"USER_PROFILE_UPDATED\",\"userId\":\"%s\",\"version\":1234}".formatted(id("alice")));
        deleted(id("someone-with-no-room"));
        announce("not even json");
        // A deletion that follows proves the listener is still alive after what came before it.
        String carols = createRoom("carol", "Carol's room", 3);
        deleted(id("carol"));
        await("the listener survived the bad message", 10_000, () -> !openRoomIds().contains(carols));

        assertTrue(openRoomIds().contains(roomId));
    }

    @Test
    void hearingTheSameDeletionTwiceIsHarmless() {
        String roomId = createRoom("alice", "Once", 3);

        deleted(id("alice"));
        deleted(id("alice"));
        await("the room closes", 10_000, () -> !openRoomIds().contains(roomId));

        assertEquals(1, gateway.closedRooms.stream().filter(uuid(roomId)::equals).count(), "the media room is removed once");
    }
}
