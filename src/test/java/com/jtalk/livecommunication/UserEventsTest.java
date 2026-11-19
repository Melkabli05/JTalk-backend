package com.jtalk.livecommunication;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.repository.VoiceRoomRepository;
import com.jtalk.livecommunication.support.ApiTestBase;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the user service announces: an account that is deleted takes the rooms it was running with it. */
class UserEventsTest extends ApiTestBase {

    private static final String CHANNEL = "user-events";

    @Inject
    StatefulRedisConnection<String, String> redis;

    @Inject
    VoiceRoomRepository repository;

    private void announce(String json) {
        redis.sync().publish(CHANNEL, json);
    }

    private void deleted(UUID userId) {
        announce("{\"type\":\"USER_DELETED\",\"userId\":\"%s\"}".formatted(userId));
    }

    private String hostOf(String roomId) {
        return String.valueOf(getRoom(roomId).get("hostId"));
    }

    @SuppressWarnings("unchecked")
    private List<String> speakersOf(String roomId) {
        return ((List<Object>) getStage(roomId).get("speakers")).stream().map(String::valueOf).toList();
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
        await("its media room is removed, after the room closed and off the request", 10_000, () -> gateway.closedRooms.contains(uuid(roomId)));

        assertEquals(1, gateway.closedRooms.stream().filter(uuid(roomId)::equals).count(), "the media room is removed once");
    }

    // ---- the rules when the deleted person ran a room with other people on its stage ---------------------

    @Test
    void aRoomWithSomeoneElseOnItsStageCarriesOnUnderTheOneWhoHasBeenThereLongest() {
        String roomId = createRoom("alice", "Spanish coffee", 4);
        join(roomId, "alice");
        seatSpeaker(roomId, "bob");
        seatSpeaker(roomId, "carol");
        join(roomId, "dave");

        deleted(id("alice"));

        await("the room is handed over", 10_000, () -> hostOf(roomId).equals(id("bob").toString()));
        assertTrue(openRoomIds().contains(roomId), "the room stays open: someone on the stage can run it");
        await("alice is off the stage", 10_000, () -> !speakersOf(roomId).contains(id("alice").toString()));
        assertEquals(List.of(id("bob").toString(), id("carol").toString()), speakersOf(roomId).stream().sorted(
                java.util.Comparator.comparing(id("bob").toString()::equals).reversed()).toList(), "bob and carol are still on it");
        assertTrue(gateway.removals.stream().anyMatch(r -> r.userId().equals(id("alice")) && r.roomId().equals(uuid(roomId))),
                "alice's media connection is cut and her tokens revoked");
        assertTrue(gateway.events.stream().anyMatch(e -> e.type() == RoomEventType.ROOM_UPDATED && e.settings() != null
                && e.settings().hostId().equals(id("bob"))), "everyone in the room is told who hosts it now");
    }

    @Test
    void theNewHostCanRunTheRoomAndTheOldOneCannotAnyMore() {
        String roomId = createRoom("alice", "Handed over", 4);
        seatSpeaker(roomId, "bob");
        join(roomId, "carol");
        postEmpty(roomId, "/stage/raise-hand", "carol");

        deleted(id("alice"));
        await("handed over", 10_000, () -> hostOf(roomId).equals(id("bob").toString()));

        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/promote/" + id("carol"), "bob").status(), "bob can invite now");
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> postEmpty(roomId, "/stage/promote/" + id("dave"), "alice"));
    }

    @Test
    void aRoomWithOnlyListenersLeftHasNoOneToRunItAndCloses() {
        String roomId = createRoom("alice", "Listeners only", 4);
        join(roomId, "bob");
        join(roomId, "carol");
        postEmpty(roomId, "/stage/raise-hand", "carol");

        deleted(id("alice"));

        await("the room closes", 10_000, () -> !openRoomIds().contains(roomId));
    }

    @Test
    void aSuccessorWhoIsDeletedInTurnPassesItOnOrCloses() {
        String roomId = createRoom("alice", "Passed on", 4);
        seatSpeaker(roomId, "bob");
        seatSpeaker(roomId, "carol");

        deleted(id("alice"));
        await("bob hosts", 10_000, () -> hostOf(roomId).equals(id("bob").toString()));
        deleted(id("bob"));
        await("carol hosts", 10_000, () -> hostOf(roomId).equals(id("carol").toString()));
        deleted(id("carol"));

        await("with no one left the room closes", 10_000, () -> !openRoomIds().contains(roomId));
    }

    // ---- the rules when the deleted person was only in other people's rooms ------------------------------

    @Test
    void aDeletedSpeakerAndAHandRaisedByADeletedListenerAreRemovedFromTheRoom() {
        String roomId = createRoom("alice", "Alice's room", 4);
        seatSpeaker(roomId, "bob");
        join(roomId, "carol");
        postEmpty(roomId, "/stage/raise-hand", "carol");

        deleted(id("bob"));
        deleted(id("carol"));

        await("bob leaves the stage", 10_000, () -> !speakersOf(roomId).contains(id("bob").toString()));
        await("carol's hand is gone", 10_000, () -> ((List<?>) getStage(roomId).get("handRaiseQueue")).isEmpty());
        assertEquals(List.of(id("alice").toString()), speakersOf(roomId), "only the host is left on the stage");
        assertTrue(openRoomIds().contains(roomId), "the room carries on");
        assertTrue(gateway.removals.stream().anyMatch(r -> r.userId().equals(id("bob"))), "bob's media connection is cut");
        assertFalse(getParticipants(roomId).stream().anyMatch(p -> id("bob").toString().equals(String.valueOf(p.get("userId")))),
                "and bob is no longer listed");
    }

    @Test
    void aDeletedListenerWhoIsInNoRoomChangesNothing() {
        String roomId = createRoom("alice", "Quiet", 4);
        join(roomId, "bob");

        deleted(id("zed"));
        deleted(id("carol"));
        deleted(id("bob"));
        await("bob is gone", 10_000, () -> getParticipants(roomId).stream().noneMatch(
                p -> id("bob").toString().equals(String.valueOf(p.get("userId")))));

        assertEquals(id("alice").toString(), hostOf(roomId));
        assertTrue(gateway.removals.stream().noneMatch(r -> r.userId().equals(id("zed")) || r.userId().equals(id("carol"))));
    }

    // ---- what a person's own words leave behind ---------------------------------------------------------

    @Test
    void everyRoomTheyEverHostedIsErased_theClosedOnesToo_butSomeoneElsesAreKept() {
        String old = createRoom("alice", "Alice's old room", 3);
        assertEquals(HttpStatus.NO_CONTENT, delete(old, "", "alice").status());
        String current = createRoom("alice", "Alice's current room", 3);
        String carols = createRoom("carol", "Carol's room", 3);
        assertTrue(repository.findById(uuid(old)).isPresent(), "a closed room is kept as history until its host is deleted");

        deleted(id("alice"));

        await("her rooms are erased", 10_000, () -> repository.findById(uuid(old)).isEmpty() && repository.findById(uuid(current)).isEmpty());
        assertTrue(repository.findById(uuid(carols)).isPresent(), "carol's room is not touched");
    }

    @Test
    void aRoomHandedOverIsNotErasedWithItsOldHost() {
        String roomId = createRoom("alice", "Handed over", 4);
        seatSpeaker(roomId, "bob");

        deleted(id("alice"));
        await("handed over", 10_000, () -> hostOf(roomId).equals(id("bob").toString()));

        assertTrue(repository.findById(uuid(roomId)).isPresent(), "the room belongs to bob now");
        assertEquals("Handed over", getRoom(roomId).get("title"));
    }
}
