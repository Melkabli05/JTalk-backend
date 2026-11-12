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
        List<Map<String, Object>> active = listRooms("?size=100");
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
        active = listRooms("?size=100");
        assertFalse(active.stream().anyMatch(r -> roomId.equals(r.get("id"))));
    }

    @Test
    void hostCanUpdateRoomSettingsAndNobodyElse() {
        String roomId = createRoom("host", "Old name", 4);

        Map<String, Object> updated = http.retrieve(HttpRequest.PATCH(path(roomId, ""), Map.of("name", "  New name ", "maxSpeakers", 3))
                .bearerAuth(token("host")), MAP);
        assertEquals("New name", updated.get("name"));
        assertEquals(3, updated.get("maxSpeakers"));

        // Partial update keeps what is not sent.
        Map<String, Object> renamedOnly = http.retrieve(HttpRequest.PATCH(path(roomId, ""), Map.of("name", "Only name"))
                .bearerAuth(token("host")), MAP);
        assertEquals(3, renamedOnly.get("maxSpeakers"));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("name", "Hijack")).bearerAuth(token("alice"))));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("name", "   ")).bearerAuth(token("host"))));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("maxSpeakers", 99)).bearerAuth(token("host"))));
        assertEquals("Only name", http.retrieve(HttpRequest.GET(path(roomId, "")), MAP).get("name"));
    }

    @Test
    void roomsShowLiveStageAndPresenceNumbers() {
        String roomId = createRoom("host", "Live numbers", 2);
        Map<String, Object> fresh = http.retrieve(HttpRequest.GET(path(roomId, "")), MAP);
        assertEquals(List.of(), fresh.get("speakers"));   // an empty stage is still in the payload
        assertEquals(0, fresh.get("participantCount"));

        post(roomId, "/participants/me", "host");
        seatSpeaker(roomId, "host", "alice");
        post(roomId, "/participants/me", "bob");
        post(roomId, "/stage/hand-raises", "bob");

        Map<String, Object> room = http.retrieve(HttpRequest.GET(path(roomId, "")), MAP);
        assertEquals(List.of("alice"), room.get("speakers"));
        assertEquals(3, room.get("participantCount"));   // host, alice, bob
        assertEquals(1, room.get("handRaiseCount"));

        List<Map<String, Object>> listed = listRooms("?size=100");
        Map<String, Object> inList = listed.stream().filter(r -> roomId.equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals(List.of("alice"), inList.get("speakers"));
        assertEquals(3, inList.get("participantCount"));
        assertEquals(1, inList.get("handRaiseCount"));
    }

    @Test
    void discoveryUsesKeysetPaginationWithoutOverlap() {
        createRoom("host", "page-1", 2);
        createRoom("host", "page-2", 2);
        createRoom("host", "page-3", 2);

        List<Map<String, Object>> first = listRooms("?size=2");
        Map<String, Object> last = first.getLast();
        List<Map<String, Object>> second = listRooms("?size=2&afterCreatedAt=" + last.get("createdAt") + "&afterId=" + last.get("id"));

        assertEquals(2, first.size());
        assertEquals("page-3", first.getFirst().get("name"), "newest first");
        assertFalse(second.isEmpty());
        var firstIds = first.stream().map(r -> r.get("id")).toList();
        assertTrue(second.stream().noneMatch(r -> firstIds.contains(r.get("id"))), "pages must not overlap");
        assertEquals("page-1", second.getFirst().get("name"), "the page after the last room continues the order");
    }

    @Test
    void discoveryPageSizeIsBounded() {
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> listRooms("?size=1000"));
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() {
        var body = Map.of("name", "No caller");
        var missing = assertThrows(HttpClientResponseException.class, () -> http.exchange(HttpRequest.POST("/api/v1/voice-rooms", body)));
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatus());
        var forged = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token("host", "another-secret-another-secret-another-secret"))));
        assertEquals(HttpStatus.UNAUTHORIZED, forged.getStatus());
    }

    @Test
    void validationErrorsAreProblemDetails() {
        var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", Map.of("name", " ", "maxSpeakers", 99))
                        .bearerAuth(token("host")), MAP));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("application/problem+json", ex.getResponse().getContentType().orElseThrow().toString());
    }

    @Test
    void unknownRoomIsA404Problem() {
        UUID missing = UUID.randomUUID();
        var problem = assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found",
                () -> http.exchange(HttpRequest.GET("/api/v1/voice-rooms/" + missing), MAP));
        assertEquals("Voice room not found", problem.get("title"));
        assertTrue(String.valueOf(problem.get("detail")).contains(missing.toString()));
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
