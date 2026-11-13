package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.PermissionChange;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
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
        Map<String, Object> room = createRoom("host", Map.of("title", "Spanish exchange", "targetLanguage", "es", "nativeLanguage", "en", "maxStageSeats", 3));
        String roomId = (String) room.get("id");
        assertEquals(ids("host"), room.get("speakers"), "the host is seated when the room is created");
        assertEquals(List.of(uuid(roomId)), gateway.createdRooms);
        assertTrue(listRooms("?size=100&targetLanguage=es").stream().anyMatch(r -> roomId.equals(r.get("id"))));

        // Host and listeners join; only people on stage may publish
        Map<String, Object> hostSession = join(roomId, "host");
        assertEquals("HOST", hostSession.get("role"));
        assertEquals(true, hostSession.get("canPublishAudio"));
        assertEquals("token:" + id("host") + ":publish=true", hostSession.get("liveKitToken"));
        Map<String, Object> aliceSession = join(roomId, "alice");
        assertEquals("LISTENER", aliceSession.get("role"));
        assertEquals(false, aliceSession.get("canPublishAudio"));
        join(roomId, "bob");
        join(roomId, "carol");

        // Alice takes a free seat herself and is granted publish rights on the media server
        Map<String, Object> claimed = post(roomId, "/stage/join", "alice");
        assertEquals("SPEAKER", claimed.get("role"));
        assertEquals(true, claimed.get("canPublishAudio"));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("alice"), true)));

        // Bob raises a hand while a seat is still free; then Carol takes the last seat, so the stage is full
        assertEquals(HttpStatus.ACCEPTED, postEmpty(roomId, "/stage/raise-hand", "bob").status());
        post(roomId, "/stage/join", "carol");
        assertProblem(HttpStatus.CONFLICT, "stage-full", () -> post(roomId, "/stage/join", "dave"));
        Map<String, Object> stage = getStage(roomId);
        assertEquals(new HashSet<>(ids("host", "alice", "carol")), new HashSet<>((List<?>) stage.get("speakers")));
        assertEquals(ids("bob"), stage.get("handRaiseQueue"));

        // Roles are visible to everyone in the room
        assertEquals(Map.of(id("host").toString(), "HOST", id("alice").toString(), "SPEAKER",
                id("bob").toString(), "LISTENER", id("carol").toString(), "SPEAKER"), rolesByUser(getParticipants(roomId)));

        // Alice steps down: the longest-waiting hand-raiser (Bob) takes her seat, atomically
        gateway.permissionChanges.clear();
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/leave", "alice").status());
        assertEquals(new HashSet<>(ids("host", "carol", "bob")), new HashSet<>((List<?>) getStage(roomId).get("speakers")));
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("alice"), false)));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), true)));

        // Closing: only the host; then the room is gone from discovery and cannot be joined
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(roomId, "", "alice"));
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "", "host").status());
        assertEquals(List.of(uuid(roomId)), gateway.closedRooms);
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> join(roomId, "alice"));
        assertFalse(listRooms("?size=100&targetLanguage=es").stream().anyMatch(r -> roomId.equals(r.get("id"))));
    }

    @Test
    void roomDefaultsAndTheHostsFixedSeat() {
        Map<String, Object> room = createRoom("host", Map.of("title", "Defaults", "targetLanguage", "en", "nativeLanguage", "ar"));
        String roomId = (String) room.get("id");
        assertEquals(8, room.get("maxStageSeats"));
        assertEquals(true, room.get("allowWhisper"));
        assertEquals(id("host").toString(), room.get("hostId"));

        join(roomId, "host");
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "host-cannot-leave-stage", () -> postEmpty(roomId, "/stage/leave", "host"));
        assertProblem(HttpStatus.CONFLICT, "already-on-stage", () -> postEmpty(roomId, "/stage/raise-hand", "host"));
    }

    @Test
    void hostCanUpdateRoomSettingsAndNobodyElse() {
        String roomId = createRoom("host", "Old title", 4);

        Map<String, Object> updated = http.retrieve(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "  New title ", "topic", "Travel", "maxStageSeats", 3))
                .bearerAuth(token("host")), MAP);
        assertEquals("New title", updated.get("title"));
        assertEquals("Travel", updated.get("topic"));
        assertEquals(3, updated.get("maxStageSeats"));

        Map<String, Object> renamedOnly = http.retrieve(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "Only title"))
                .bearerAuth(token("host")), MAP);
        assertEquals(3, renamedOnly.get("maxStageSeats"), "a partial update keeps what is not sent");
        assertEquals("Travel", renamedOnly.get("topic"));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("title", "Hijack")).bearerAuth(token("alice"))));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("title", "   ")).bearerAuth(token("host"))));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("maxStageSeats", 99)).bearerAuth(token("host"))));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("maxStageSeats", 1)).bearerAuth(token("host"))));
        assertEquals("Only title", getRoom(roomId).get("title"));
    }

    @Test
    void roomsShowLiveStageAndPresenceNumbers() {
        String roomId = createRoom("host", "Live numbers", 3);
        Map<String, Object> fresh = getRoom(roomId);
        assertEquals(ids("host"), fresh.get("speakers"));
        assertEquals(0, fresh.get("participantCount"));

        join(roomId, "host");
        seatSpeaker(roomId, "alice");
        join(roomId, "bob");
        postEmpty(roomId, "/stage/raise-hand", "bob");

        Map<String, Object> room = getRoom(roomId);
        assertEquals(new HashSet<>(ids("host", "alice")), new HashSet<>((List<?>) room.get("speakers")));
        assertEquals(3, room.get("participantCount"));
        assertEquals(1, room.get("handRaiseCount"));

        Map<String, Object> inList = listRooms("?size=100").stream().filter(r -> roomId.equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals(3, inList.get("participantCount"));
        assertEquals(1, inList.get("handRaiseCount"));
    }

    @Test
    void discoveryUsesKeysetPaginationAndFiltersByLanguage() {
        for (String title : List.of("page-1", "page-2", "page-3")) {
            createRoom("host", Map.of("title", title, "targetLanguage", "kp", "nativeLanguage", "en"));
        }
        createRoom("host", Map.of("title", "other-language", "targetLanguage", "xx", "nativeLanguage", "en"));

        List<Map<String, Object>> first = listRooms("?targetLanguage=kp&size=2");
        Map<String, Object> last = first.getLast();
        List<Map<String, Object>> second = listRooms("?targetLanguage=kp&size=2&afterCreatedAt=" + last.get("createdAt") + "&afterId=" + last.get("id"));

        assertEquals(List.of("page-3", "page-2"), first.stream().map(r -> r.get("title")).toList(), "newest first");
        assertEquals(List.of("page-1"), second.stream().map(r -> r.get("title")).toList(), "the next page continues after the last room");
        assertTrue(first.stream().allMatch(r -> "kp".equals(r.get("targetLanguage"))), "the language filter applies");
    }

    @Test
    void discoveryPageSizeIsBounded() {
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> listRooms("?size=1000"));
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() {
        var body = Map.of("title", "No caller", "targetLanguage", "en", "nativeLanguage", "ar");
        var missing = assertThrows(HttpClientResponseException.class, () -> http.exchange(HttpRequest.POST("/api/v1/voice-rooms", body)));
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatus());
        var forged = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token("host", "another-secret-another-secret-another-secret"))));
        assertEquals(HttpStatus.UNAUTHORIZED, forged.getStatus());
    }

    @Test
    void aSignedTokenWhoseSubjectIsNotAUuidIsRejected() {
        var body = Map.of("title", "Odd subject", "targetLanguage", "en", "nativeLanguage", "ar");
        for (String subject : List.of("alice", "")) {
            var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                    HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(tokenWithSubject(subject, "test-jwt-secret-test-jwt-secret-0123456789"))));
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus(), "subject '" + subject + "'");
        }
    }

    @Test
    void validationErrorsAreProblemDetails() {
        Map<String, Object> body = new HashMap<>(Map.of("title", " ", "targetLanguage", "en", "nativeLanguage", "ar", "maxStageSeats", 99));
        var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token("host")), MAP));
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

    // ---- helpers ----------------------------------------------------------------------------------

    private static Map<String, String> rolesByUser(List<Map<String, Object>> participants) {
        var roles = new HashMap<String, String>();
        participants.forEach(p -> roles.put((String) p.get("userId"), (String) p.get("role")));
        return roles;
    }
}
