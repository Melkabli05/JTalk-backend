package com.jtalk.livecommunication;

import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.ReactionBurst;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reactions: told to the whole room in one batched message, only by someone who has joined, and not too often. */
class ReactionApiTest extends ApiTestBase {

    private List<ReactionBurst> reactionsTold() {
        return gateway.events.stream()
                .filter(event -> event.type() == RoomEventType.REACTIONS)
                .map(RoomRealtimeEvent::reactions)
                .flatMap(List::stream)
                .toList();
    }

    private HttpStatus react(String roomId, String user, String reaction) {
        return postJson(roomId, "/reactions", user, Map.of("reaction", reaction)).getStatus();
    }

    @Test
    void aReactionIsToldToTheWholeRoom() {
        String roomId = createRoom("host", "Reactions", 2);
        join(roomId, "host");
        join(roomId, "alice");

        assertEquals(HttpStatus.ACCEPTED, react(roomId, "alice", "HEART"));

        await("the room is told about the reaction", 5_000, () -> !reactionsTold().isEmpty());
        assertEquals(List.of(new ReactionBurst(id("alice"), Reaction.HEART)), reactionsTold());
        RoomRealtimeEvent event = gateway.events.stream().filter(e -> e.type() == RoomEventType.REACTIONS).findFirst().orElseThrow();
        assertEquals(uuid(roomId), event.roomId());
    }

    @Test
    void reactionsFromDifferentPeopleAreAllTold() {
        String roomId = createRoom("host", "Many reactions", 2);
        join(roomId, "host");
        join(roomId, "alice");
        join(roomId, "bob");

        react(roomId, "alice", "SMILE");
        react(roomId, "bob", "CLAP");

        await("both reactions are told", 5_000, () -> reactionsTold().size() == 2);
        assertTrue(reactionsTold().contains(new ReactionBurst(id("alice"), Reaction.SMILE)));
        assertTrue(reactionsTold().contains(new ReactionBurst(id("bob"), Reaction.CLAP)));
    }

    @Test
    void someoneWhoHasNotJoinedCannotReact() {
        String roomId = createRoom("host", "Outsiders", 2);
        join(roomId, "host");

        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> react(roomId, "stranger", "HEART"));
        assertTrue(reactionsTold().isEmpty());
    }

    @Test
    void reactingAgainAtOnceIsRefusedAndAfterAMomentAllowed() throws InterruptedException {
        String roomId = createRoom("host", "Slow down", 2);
        join(roomId, "host");
        join(roomId, "alice");

        assertEquals(HttpStatus.ACCEPTED, react(roomId, "alice", "THUMBS_UP"));
        assertProblem(HttpStatus.TOO_MANY_REQUESTS, "reacting-too-fast", () -> react(roomId, "alice", "THUMBS_UP"));

        Thread.sleep(700);
        assertEquals(HttpStatus.ACCEPTED, react(roomId, "alice", "THUMBS_UP"));
    }

    @Test
    void anUnknownReactionIsABadRequest() {
        String roomId = createRoom("host", "Unknown", 2);
        join(roomId, "host");

        var failure = assertThrows(HttpClientResponseException.class, () -> react(roomId, "host", "FIREWORKS"));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
    }

    @Test
    void aRoomThatDoesNotExistIsNotFound() {
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> react(UUID.randomUUID().toString(), "alice", "HEART"));
    }
}
