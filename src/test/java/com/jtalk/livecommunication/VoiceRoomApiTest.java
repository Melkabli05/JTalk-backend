package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeMediaService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceRoomApiTest extends ApiTestBase {

    @Test
    void fullRoomLifecycleWithStageAndPresence() {
        String roomId = createRoom("host", "Spanish exchange", 1);

        // Discovery
        List<Map<String, Object>> active = http.retrieve(HttpRequest.GET("/api/v1/voice-rooms"), MAP_LIST);
        assertTrue(active.stream().anyMatch(r -> roomId.equals(r.get("id"))));

        // Host and listener join; only the host may publish
        Map<String, Object> hostJoin = post(roomId, "/participants/me", "host");
        assertEquals("HOST", hostJoin.get("role"));
        assertTrue(hostJoin.get("mediaToken").toString().endsWith("publish=true"));
        Map<String, Object> aliceJoin = post(roomId, "/participants/me", "alice");
        assertEquals("LISTENER", aliceJoin.get("role"));
        assertTrue(aliceJoin.get("mediaToken").toString().endsWith("publish=false"));
        post(roomId, "/participants/me", "bob");

        // Alice and Bob raise hands in order
        assertEquals(1, post(roomId, "/stage/hand-raises", "alice").get("queuePosition"));
        assertEquals(2, post(roomId, "/stage/hand-raises", "bob").get("queuePosition"));

        // Only the host can approve
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> put(roomId, "/stage/speakers/alice", "bob"));

        // Host approves Alice -> seated and granted publish rights on the media server
        put(roomId, "/stage/speakers/alice", "host");
        assertEquals(List.of(new FakeMediaService.PermissionChange(UUID.fromString(roomId), "alice", true)),
                media.permissionChanges);

        // Single seat: approving Bob is a conflict and his hand stays raised
        assertProblem(HttpStatus.CONFLICT, "stage-full", () -> put(roomId, "/stage/speakers/bob", "host"));
        Map<String, Object> stage = http.retrieve(HttpRequest.GET(path(roomId, "/stage")), MAP);
        assertEquals(List.of("alice"), stage.get("speakers"));
        assertEquals(List.of("bob"), stage.get("handRaiseQueue"));

        // Roles are visible to everyone in the room
        List<Map<String, Object>> present = http.retrieve(HttpRequest.GET(path(roomId, "/participants")), MAP_LIST);
        assertEquals(Map.of("host", "HOST", "alice", "SPEAKER", "bob", "LISTENER"), rolesByUser(present));

        // Alice rejoining keeps publish rights because she still holds a seat
        assertTrue(post(roomId, "/participants/me", "alice").get("mediaToken").toString().endsWith("publish=true"));

        // Alice steps down -> seat frees, publish revoked, Bob can now be seated
        delete(roomId, "/stage/speakers/alice", "alice");
        put(roomId, "/stage/speakers/bob", "host");

        // Leaving clears stage state
        delete(roomId, "/participants/me", "bob");
        assertEquals(List.of(), http.retrieve(HttpRequest.GET(path(roomId, "/stage")), MAP).get("speakers"));

        // Closing: only the host, then the room is gone from discovery and unusable
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> deleteRoom(roomId, "alice"));
        HttpResponse<?> closed = deleteRoom(roomId, "host");
        assertEquals(HttpStatus.NO_CONTENT, closed.status());
        assertEquals(List.of(UUID.fromString(roomId)), media.closedRooms);
        assertProblem(HttpStatus.CONFLICT, "voice-room-closed", () -> post(roomId, "/participants/me", "alice"));
        active = http.retrieve(HttpRequest.GET("/api/v1/voice-rooms"), MAP_LIST);
        assertFalse(active.stream().anyMatch(r -> roomId.equals(r.get("id"))));
    }

    @Test
    void validationErrorsAreProblemDetails() {
        var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", Map.of("name", " ", "maxSpeakers", 99))
                        .header("X-User-Id", "host"), MAP));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("application/problem+json", ex.getResponse().getContentType().orElseThrow().toString());
    }

    @Test
    void unknownRoomIsA404Problem() {
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found",
                () -> http.exchange(HttpRequest.GET("/api/v1/voice-rooms/" + UUID.randomUUID()), MAP));
    }

    @Test
    void hostCannotRaiseHand() {
        String roomId = createRoom("host", "Solo", null);
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "host-does-not-need-seat",
                () -> post(roomId, "/stage/hand-raises", "host"));
    }

    private static Map<String, String> rolesByUser(List<Map<String, Object>> participants) {
        var roles = new java.util.HashMap<String, String>();
        participants.forEach(p -> roles.put((String) p.get("userId"), (String) p.get("role")));
        return roles;
    }
}
