package com.jtalk.livecommunication;

import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.FakeAuth0;
import com.jtalk.livecommunication.support.FakeLiveKitGateway;
import com.jtalk.livecommunication.support.FakeLiveKitGateway.PermissionChange;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Inject;
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

    @Inject
    StatefulRedisConnection<String, String> redis;

    @Test
    @SuppressWarnings("unchecked")
    void expandSpeakersPopulatesHostAndSpeakerSummariesFromRedis() {
        redis.sync().hset("user:profile:{" + id("host") + "}", Map.of(
                "status", "ACTIVE",
                "username", "host_user",
                "displayName", "Host User",
                "avatarUrl", "/avatars/preset-1.svg",
                "countryCode", "MA",
                "nativeLanguage", "ar"));
        String room = (String) createRoom("host", Map.of("title", "Expanded Room", "targetLanguage", "ja", "nativeLanguage", "ar")).get("id");

        Map<String, Object> expanded = getRoom(room + "?expand=speakers");
        Map<String, Object> host = (Map<String, Object>) expanded.get("host");
        assertEquals(id("host").toString(), host.get("id"));
        assertEquals("Host User", host.get("displayName"));
        List<Map<String, Object>> speakerProfiles = (List<Map<String, Object>>) expanded.get("speakerProfiles");
        assertEquals(1, speakerProfiles.size());
        assertEquals("host_user", speakerProfiles.get(0).get("username"));

        List<Map<String, Object>> items = listRooms("?targetLanguage=ja&expand=speakers");
        assertEquals(1, items.size());
        assertEquals("Host User", ((Map<String, Object>) items.get(0).get("host")).get("displayName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJoinSnapshotIncludesTheHostsRestoredSeat() {
        String room = createRoom("host", "Restored stage", 6);
        redis.sync().zrem("voiceroom:{" + room + "}:stage", id("host").toString());

        var session = join(room, "host");
        assertEquals(true, session.get("canPublishAudio"));
        var snapshot = (Map<String, Object>) session.get("snapshot");
        var speakers = (List<Map<String, Object>>) snapshot.get("speakers");
        assertEquals(List.of(id("host").toString()), speakers.stream().map(speaker -> speaker.get("userId")).toList());
        assertEquals(0, ((Number) snapshot.get("audienceCount")).intValue());
        assertEquals(List.of(), snapshot.get("handRaiseQueue"));
        assertEquals(List.of(), ((Map<?, ?>) snapshot.get("audience")).get("audiencePreview"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinCapsAudienceButReturnsTheExactCountAndAContinuation() {
        String room = createRoom("host", "Big room", 6);
        var listeners = java.util.stream.IntStream.range(0, 300)
                .mapToObj(i -> ScoredValue.just((double) Long.MAX_VALUE, id("listener-" + i).toString()))
                .toArray(ScoredValue[]::new);
        redis.sync().zadd("voiceroom:{" + room + "}:presence", listeners);
        redis.sync().hset("user:profile:{" + id("host") + "}",
                Map.of("status", "ACTIVE", "displayName", "Cached host", "nativeLanguage", "en"));

        Map<String, Object> snapshot = (Map<String, Object>) join(room, "host").get("snapshot");
        assertEquals(300, ((Number) snapshot.get("audienceCount")).intValue());
        var stage = (List<Map<String, Object>>) snapshot.get("speakers");
        assertEquals(1, stage.size());
        assertEquals("Cached host", stage.getFirst().get("displayName"));
        var first = (Map<String, Object>) snapshot.get("audience");
        var preview = (List<Map<String, Object>>) first.get("audiencePreview");
        assertEquals(20, preview.size());
        assertEquals(true, first.get("hasMoreAudience"));
        assertTrue(preview.stream().noneMatch(item -> id("host").toString().equals(item.get("userId"))));
        assertTrue(preview.stream().allMatch(item -> item.get("displayName") == null));

        var page = http.retrieve(HttpRequest.GET(path(room, "/participants/audience?cursor=" + first.get("nextCursor")))
                .bearerAuth(token("host")), MAP);
        var next = (List<Map<String, Object>>) page.get("audiencePreview");
        assertEquals(20, next.size());
        var ids = new HashSet<>(preview.stream().map(item -> item.get("userId")).toList());
        assertTrue(next.stream().noneMatch(item -> ids.contains(item.get("userId"))));
        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> http.exchange(
                HttpRequest.GET(path(room, "/participants/audience")).bearerAuth(token("outsider"))));
        redis.sync().zrem("voiceroom:{" + room + "}:presence", (String) first.get("nextCursor"));
        var expired = http.retrieve(HttpRequest.GET(path(room, "/participants/audience?cursor=" + first.get("nextCursor")))
                .bearerAuth(token("host")), MAP);
        assertEquals(List.of(), expired.get("audiencePreview"));
        assertEquals(false, expired.get("hasMoreAudience"));
        redis.sync().del("user:profile:{" + id("host") + "}");
    }

    @Test
    void reconnectSnapshotRequiresMembershipAndLeavesRedisPresenceAndGraceLeaseIntact() {
        String room = createRoom("host", "Reconnect", 6);
        var url = path(room, "/participants/snapshot");
        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> http.exchange(
                HttpRequest.GET(url).bearerAuth(token("outsider"))));

        join(room, "host");
        seatSpeaker(room, "guest");
        String presenceKey = "voiceroom:{" + room + "}:presence";
        String guestId = id("guest").toString();
        double seen = redis.sync().zscore(presenceKey, guestId) - 1_000;
        redis.sync().zadd(presenceKey, seen, guestId);
        String leaseKey = "voiceroom:{" + room + "}:lease:" + guestId;
        redis.sync().set(leaseKey, "1");

        var result = http.retrieve(HttpRequest.GET(url).bearerAuth(token("guest")), MAP);
        assertEquals("SPEAKER", result.get("role"));
        assertFalse(result.containsKey("mediaToken"));
        var snapshot = (Map<?, ?>) result.get("snapshot");
        assertEquals(0, ((Number) snapshot.get("audienceCount")).intValue());
        assertEquals(List.of(), snapshot.get("handRaiseQueue"));
        assertEquals(List.of(), ((Map<?, ?>) snapshot.get("audience")).get("audiencePreview"));
        assertTrue(((List<?>) snapshot.get("speakers")).stream().map(Map.class::cast)
                .anyMatch(person -> guestId.equals(person.get("userId")) && Boolean.TRUE.equals(person.get("isReconnecting"))));
        assertEquals(seen, redis.sync().zscore(presenceKey, guestId));
        assertEquals("1", redis.sync().get(leaseKey));
        redis.sync().del(leaseKey);
    }

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
        assertEquals("token:" + id("host") + ":publish=true", hostSession.get("mediaToken"));
        assertEquals(FakeLiveKitGateway.SERVER_URL, hostSession.get("mediaUrl"), "clients are told where to connect");
        Map<String, Object> aliceSession = join(roomId, "alice");
        assertEquals("LISTENER", aliceSession.get("role"));
        assertEquals(false, aliceSession.get("canPublishAudio"));
        join(roomId, "bob");
        join(roomId, "carol");

        // Alice cannot claim a stage seat directly as a LISTENER (403); host promotes her
        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> post(roomId, "/stage/join", "alice"));
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/promote/" + id("alice"), "host").status());
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("alice"), true)));

        // Bob raises a hand while a seat is still free; Carol is made MODERATOR and joins the last seat directly
        assertEquals(HttpStatus.ACCEPTED, postEmpty(roomId, "/stage/raise-hand", "bob").status());
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/moderator/" + id("carol"), "host").status());
        Map<String, Object> claimed = post(roomId, "/stage/join", "carol");
        assertEquals("MODERATOR", claimed.get("role"));
        assertEquals(true, claimed.get("canPublishAudio"));
        assertProblem(HttpStatus.CONFLICT, "stage-full", () -> postEmpty(roomId, "/stage/promote/" + id("bob"), "carol"));
        Map<String, Object> stage = getStage(roomId);
        assertEquals(new HashSet<>(ids("host", "alice", "carol")), new HashSet<>((List<?>) stage.get("speakers")));
        assertEquals(ids("bob"), stage.get("handRaiseQueue"));

        // Roles are visible to everyone in the room
        assertEquals(Map.of(id("host").toString(), "HOST", id("alice").toString(), "SPEAKER",
                id("bob").toString(), "LISTENER", id("carol").toString(), "MODERATOR"), rolesByUser(getParticipants(roomId)));

        // Alice steps down: freed seat stays open (no queue auto-promotion) until moderator Carol approves Bob
        gateway.permissionChanges.clear();
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/leave", "alice").status());
        assertEquals(new HashSet<>(ids("host", "carol")), new HashSet<>((List<?>) getStage(roomId).get("speakers")));
        assertEquals(ids("bob"), getStage(roomId).get("handRaiseQueue"));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("alice"), false)));

        assertEquals(HttpStatus.NO_CONTENT, postEmpty(roomId, "/stage/promote/" + id("bob"), "carol").status());
        assertEquals(new HashSet<>(ids("host", "carol", "bob")), new HashSet<>((List<?>) getStage(roomId).get("speakers")));
        assertEquals(List.of(), getStage(roomId).get("handRaiseQueue"));
        assertTrue(gateway.permissionChanges.contains(new PermissionChange(uuid(roomId), id("bob"), true)));

        // Closing: only the host; then the room is gone from discovery and cannot be joined
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(roomId, "", "alice"));
        assertEquals(HttpStatus.NO_CONTENT, delete(roomId, "", "host").status());
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> join(roomId, "alice"));
        await("the media room is deleted after the answer, off the request", 5_000, () -> gateway.closedRooms.contains(uuid(roomId)));
        assertEquals(List.of(uuid(roomId)), gateway.closedRooms);
        assertFalse(listRooms("?size=100&targetLanguage=es").stream().anyMatch(r -> roomId.equals(r.get("id"))));
    }

    @Test
    void aFailedMediaServerLeavesNoHalfCreatedRoomBehind() {
        var body = Map.of("title", "Doomed", "targetLanguage", "or", "nativeLanguage", "en");
        gateway.createRoomFailure = ProblemType.MEDIA_SERVER_UNREACHABLE.because("LiveKit is down");

        assertProblem(HttpStatus.BAD_GATEWAY, "media-server-unreachable", () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token("host")), MAP));

        assertEquals(List.of(), listRooms("?targetLanguage=or&size=50"), "the row is rolled back, so discovery never shows a room with no stage");
        gateway.createRoomFailure = null;
        assertEquals("Doomed", createRoom("host", Map.of("title", "Doomed", "targetLanguage", "or", "nativeLanguage", "en")).get("title"));
    }

    @Test
    void aHostWhoAlreadyHasAnActiveRoomRejoinsItInsteadOfOpeningASecondOne() {
        Map<String, Object> first = createRoom("host", Map.of("title", "Only one", "targetLanguage", "rj", "nativeLanguage", "en"));
        Map<String, Object> again = createRoom("host", Map.of("title", "Ignored", "targetLanguage", "rj", "nativeLanguage", "en"));

        assertEquals(first.get("id"), again.get("id"), "same room comes back");
        assertEquals(1, listRooms("?targetLanguage=rj&size=50").size(), "no second room was opened");
        assertEquals("Only one", again.get("title"));
    }

    @Test
    void aClientStillSendingTheRemovedAllowWhisperFieldIsNotRejected() {
        Map<String, Object> room = createRoom("host", Map.of("title", "Old client", "targetLanguage", "en", "nativeLanguage", "ar", "allowWhisper", true));

        assertEquals("Old client", room.get("title"));
        assertFalse(room.containsKey("allowWhisper"), "the field is gone from responses");
    }

    @Test
    void roomDefaultsAndTheHostsFixedSeat() {
        Map<String, Object> room = createRoom("host", Map.of("title", "Defaults", "targetLanguage", "en", "nativeLanguage", "ar"));
        String roomId = (String) room.get("id");
        assertEquals(8, room.get("maxStageSeats"));
        assertEquals(id("host").toString(), room.get("hostId"));

        join(roomId, "host");
        assertProblem(HttpStatus.FORBIDDEN, "host-cannot-leave-stage", () -> postEmpty(roomId, "/stage/leave", "host"));
        assertProblem(HttpStatus.CONFLICT, "already-on-stage", () -> postEmpty(roomId, "/stage/raise-hand", "host"));
    }

    @Test
    void whenTheHostChangesTheRoomTheOthersInItAreToldWhatChanged() {
        String roomId = createRoom("host", "Old title", 4);
        gateway.events.clear();

        http.exchange(HttpRequest.PATCH(path(roomId, ""), Map.of("title", "New title", "topic", "Travel", "maxStageSeats", 3)).bearerAuth(token("host")));
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> http.exchange(
                HttpRequest.PATCH(path(roomId, ""), Map.of("title", "Hijack")).bearerAuth(token("alice"))));

        var told = gateway.events.stream().filter(e -> e.type() == com.jtalk.livecommunication.domain.enums.RoomEventType.ROOM_UPDATED).toList();
        assertEquals(1, told.size(), "told once, and not for an update that was refused");
        assertEquals(new com.jtalk.livecommunication.domain.model.RoomSettings("New title", "Travel", 3, id("host")), told.get(0).settings());
        assertEquals(uuid(roomId), told.get(0).roomId());
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
            createRoom("host-" + title, Map.of("title", title, "targetLanguage", "kp", "nativeLanguage", "en"));
        }
        createRoom("host-other", Map.of("title", "other-language", "targetLanguage", "xx", "nativeLanguage", "en"));

        List<Map<String, Object>> first = listRooms("?targetLanguage=kp&size=2");
        Map<String, Object> last = first.getLast();
        List<Map<String, Object>> second = listRooms("?targetLanguage=kp&size=2&afterCreatedAt=" + last.get("createdAt") + "&afterId=" + last.get("id"));

        assertEquals(List.of("page-3", "page-2"), first.stream().map(r -> r.get("title")).toList(), "newest first");
        assertEquals(List.of("page-1"), second.stream().map(r -> r.get("title")).toList(), "the next page continues after the last room");
        assertTrue(first.stream().allMatch(r -> "kp".equals(r.get("targetLanguage"))), "the language filter applies");
    }

    @Test
    void theLanguageFilterIsBoundedLikeTheLanguageItFilters() {
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> listRooms("?targetLanguage=" + "x".repeat(11)));
        assertEquals(List.of(), listRooms("?targetLanguage=" + "x".repeat(10)));
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
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(FakeAuth0.signWithAnotherKey(userToken("host")))));
        assertEquals(HttpStatus.UNAUTHORIZED, forged.getStatus(), "signed with a key that is not in the tenant's JWKS");
        var otherApi = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(FakeAuth0.sign(userToken("host").withAudience("https://some-other-api")))));
        assertEquals(HttpStatus.UNAUTHORIZED, otherApi.getStatus(), "issued for another API");
    }

    private static com.auth0.jwt.JWTCreator.Builder userToken(String name) {
        return FakeAuth0.baseToken("auth0|" + id(name)).withClaim(FakeAuth0.USER_ID_CLAIM, id(name).toString());
    }

    @Test
    void aSignedTokenWithoutAUuidUserIdClaimIsRejected() {
        var body = Map.of("title", "Odd user", "targetLanguage", "en", "nativeLanguage", "ar");
        var tokens = List.of(
                FakeAuth0.sign(FakeAuth0.baseToken("auth0|x")),                                                   // no user-id claim
                FakeAuth0.sign(FakeAuth0.baseToken("auth0|x").withClaim(FakeAuth0.USER_ID_CLAIM, "alice")),       // not a UUID
                FakeAuth0.sign(FakeAuth0.baseToken("auth0|x").withClaim(FakeAuth0.USER_ID_CLAIM, "")));
        for (String token : tokens) {
            var ex = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                    HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token)));
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        }
    }

    @Test
    void theCallerIsTheUserIdClaimNotTheSubject() {
        var body = Map.of("title", "Auth0 host", "targetLanguage", "en", "nativeLanguage", "ar");
        var created = http.retrieve(HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(FakeAuth0.sign(userToken("auth0host"))), MAP);
        assertEquals(id("auth0host").toString(), ((Map<?, ?>) created.get("room")).get("hostId"), "the subject was auth0|<uuid>");
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

    @Test
    void hostLeavingWhenAloneClosesRoomAndNonHostCannotCloseRoom() {
        String roomId = createRoom("host", "Solo Room", 6);
        post(roomId, "/participants/join", "host");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host",
                () -> delete(roomId, "", "intruder"));

        postEmpty(roomId, "/participants/leave", "host");
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> getRoom(roomId));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Map<String, String> rolesByUser(List<Map<String, Object>> participants) {
        var roles = new HashMap<String, String>();
        participants.forEach(p -> roles.put((String) p.get("userId"), (String) p.get("role")));
        return roles;
    }
}
