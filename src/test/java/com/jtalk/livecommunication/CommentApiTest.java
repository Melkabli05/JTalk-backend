package com.jtalk.livecommunication;

import com.jtalk.livecommunication.domain.enums.ChatEventType;
import com.jtalk.livecommunication.domain.model.ChatEvent;
import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A room's chat: comments and corrections, the pinned topic, and what the host does about them. The rate limit has its own test. */
class CommentApiTest extends ApiTestBase {

    @Inject
    io.lettuce.core.api.StatefulRedisConnection<String, String> redis;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        properties.put("voice-room.comments-per-window", "1000");   // the rate limit is not what these test
        return properties;
    }

    private String roomWithPeople() {
        String room = createRoom("host", "Chat", 4);
        for (String name : List.of("host", "alice", "bob")) {
            join(room, name);
        }
        return room;
    }

    private Map<String, Object> comment(String room, String user, Object body) {
        return http.retrieve(HttpRequest.POST(path(room, "/comments"), body).bearerAuth(token(user)), MAP);
    }

    private Map<String, Object> text(String content) {
        return Map.of("type", "TEXT", "content", content);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyOf(String room, String user) {
        return (List<Map<String, Object>>) chat(room, user).get("comments");
    }

    private Map<String, Object> chat(String room, String user) {
        return http.retrieve(HttpRequest.GET(path(room, "/comments")).bearerAuth(token(user)), MAP);
    }

    private HttpStatus status(HttpRequest<?> request) {
        return http.exchange(request).status();
    }

    private List<ChatEvent> events(ChatEventType type) {
        return gateway.chatEvents.stream().filter(event -> event.type() == type).toList();
    }

    @Test
    void aPersonInTheRoomCommentsAndTheRoomIsToldWithTheComment() {
        String room = roomWithPeople();

        Map<String, Object> posted = comment(room, "alice", Map.of("type", "TEXT", "content", "Hola a todos", "replyTo", id("bob").toString()));

        assertEquals(id("alice").toString(), posted.get("authorId"), "the author is the caller, never something in the body");
        assertEquals("Hola a todos", posted.get("content"));
        assertEquals("TEXT", posted.get("type"));
        assertEquals(id("bob").toString(), posted.get("replyTo"));
        assertFalse(posted.containsKey("correctedContent"));
        assertEquals(1, events(ChatEventType.COMMENT_POSTED).size());
        var told = events(ChatEventType.COMMENT_POSTED).get(0);
        assertEquals(uuid(room), told.roomId());
        assertEquals(posted.get("id"), told.comment().id().toString(), "everyone in the room gets the comment itself, not a hint to fetch it");
    }

    @Test
    void anyScriptAndEmojiAreKeptButControlCharactersAndDirectionOverridesAreNot() {
        String room = roomWithPeople();

        Map<String, Object> arabic = comment(room, "alice", text("مرحبا بالجميع 👋"));
        Map<String, Object> cleaned = comment(room, "alice", text("  hi\u0000 th‮ere\r\n\n\n\n\nbye\u0007  "));

        assertEquals("مرحبا بالجميع 👋", arabic.get("content"));
        assertEquals("hi there\n\nbye", cleaned.get("content"));
    }

    @Test
    void aCommentThatIsEmptyOnceCleanedIsRefused() {
        String room = roomWithPeople();

        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment", () -> comment(room, "alice", text("\u0000\u0007 \n ")));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> comment(room, "alice", text("")));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> comment(room, "alice", text("x".repeat(501))));
        assertEquals(List.of(), historyOf(room, "alice"));
    }

    @Test
    void aCommentOfInvisibleCharactersAloneIsNoCommentAndNeitherIsATopic() {
        String room = roomWithPeople();

        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment", () -> comment(room, "alice", text("\u200B\u200B\u2060 \u200D")));
        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment", () -> topic(room, "host", "\u200B\u200B"));
        assertEquals("👨\u200D👩\u200D👧", comment(room, "alice", text("👨\u200D👩\u200D👧")).get("content"), "a joiner inside an emoji is part of it");
        assertEquals("می\u200Cخواهم", comment(room, "alice", text("می\u200Cخواهم")).get("content"), "so is the non-joiner in Persian");
    }

    @Test
    void aCorrectionNeedsTheCorrectedPhraseAndATextCommentMustNotHaveOne() {
        String room = roomWithPeople();

        Map<String, Object> fixed = comment(room, "alice", Map.of("type", "CORRECTION", "content", "I goed home", "correctedContent", "I went home"));
        assertEquals("CORRECTION", fixed.get("type"));
        assertEquals("I went home", fixed.get("correctedContent"));

        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment", () -> comment(room, "alice", Map.of("type", "CORRECTION", "content", "I goed home")));
        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment",
                () -> comment(room, "alice", Map.of("type", "TEXT", "content", "hello", "correctedContent", "hi")));
    }

    @Test
    void someoneWhoHasNotJoinedTheRoomCannotComment() {
        String room = roomWithPeople();

        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> comment(room, "stranger", text("hello?")));
        assertEquals(List.of(), historyOf(room, "alice"));
        assertEquals(List.of(), gateway.chatEvents);
    }

    @Test
    void onlyPeopleInTheRoomReadItsChat() {
        String room = roomWithPeople();
        comment(room, "alice", text("private to the room"));

        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> chat(room, "stranger"));
        assertEquals(1, historyOf(room, "bob").size());
    }

    @Test
    void aBodyBeyondWhatAnyEndpointTakesIsRefusedBeforeItIsRead() {
        String room = roomWithPeople();

        var tooBig = org.junit.jupiter.api.Assertions.assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                () -> comment(room, "alice", text("x".repeat(100_000))));

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, tooBig.getStatus());
        assertEquals(List.of(), historyOf(room, "bob"));
    }

    @Test
    void aNewcomerGetsTheLastFiftyCommentsOldestFirstAndNoMore() {
        String room = roomWithPeople();
        for (int i = 1; i <= 55; i++) {
            comment(room, "alice", text("comment " + i));
        }

        List<Map<String, Object>> history = historyOf(room, "bob");

        assertEquals(50, history.size(), "the chat keeps a short tail, and costs the database nothing");
        assertEquals("comment 6", history.get(0).get("content"));
        assertEquals("comment 55", history.get(49).get("content"));
    }

    @Test
    void onlyTheHostDeletesCommentsAndOnlyThatCommentGoes() {
        String room = roomWithPeople();
        String first = (String) comment(room, "alice", text("first")).get("id");
        String reply = (String) comment(room, "bob", Map.of("type", "TEXT", "content", "an answer", "replyTo", first)).get("id");
        gateway.chatEvents.clear();

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(room, "/comments/" + first, "alice"));
        assertEquals(2, historyOf(room, "bob").size());
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/" + first, "host").status());

        assertEquals(List.of(reply), historyOf(room, "bob").stream().map(c -> c.get("id")).toList(), "the reply that names it stays");
        assertEquals(1, events(ChatEventType.COMMENT_DELETED).size());
        assertEquals(uuid(first), events(ChatEventType.COMMENT_DELETED).get(0).commentId());
    }

    @Test
    void deletingACommentThatIsAlreadyGoneSaysNothingToTheRoom() {
        String room = roomWithPeople();
        String id = (String) comment(room, "alice", text("hello")).get("id");
        delete(room, "/comments/" + id, "host");
        gateway.chatEvents.clear();

        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/" + id, "host").status());
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/" + UUID.randomUUID(), "host").status());

        assertEquals(List.of(), gateway.chatEvents);
    }

    @Test
    void theHostMutesSomeoneInTheChatAndLaterUnmutesThem() {
        String room = roomWithPeople();
        comment(room, "alice", text("before"));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> postEmpty(room, "/comments/mute/" + id("bob"), "alice"));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-mute-host", () -> postEmpty(room, "/comments/mute/" + id("host"), "host"));
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(room, "/comments/mute/" + id("alice"), "host").status());

        assertProblem(HttpStatus.FORBIDDEN, "chat-muted", () -> comment(room, "alice", text("after")));
        assertEquals(true, chat(room, "alice").get("muted"), "they are told when they open the chat, so it can say so");
        assertEquals(false, chat(room, "bob").get("muted"));
        assertEquals(id("alice"), events(ChatEventType.CHAT_MUTED).get(0).targetUserId());
        assertEquals("bob can", comment(room, "bob", text("bob can")).get("content"), "muting one person leaves the others");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(room, "/comments/mute/" + id("alice"), "bob"));
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/mute/" + id("alice"), "host").status());
        assertEquals("again", comment(room, "alice", text("again")).get("content"));
        assertEquals(1, events(ChatEventType.CHAT_UNMUTED).size());
        assertEquals(false, chat(room, "alice").get("muted"));
    }

    @Test
    void onlyTheHostIsToldWhoIsMuted() {
        String room = roomWithPeople();
        postEmpty(room, "/comments/mute/" + id("alice"), "host");
        postEmpty(room, "/comments/mute/" + id("bob"), "host");

        assertEquals(java.util.Set.of(id("alice").toString(), id("bob").toString()), new java.util.HashSet<>((List<?>) chat(room, "host").get("mutedUsers")));
        assertEquals(List.of(), chat(room, "bob").get("mutedUsers"), "they learn of mutes as they happen and see only whether they are muted themselves");

        delete(room, "/comments/mute/" + id("alice"), "host");
        assertEquals(List.of(id("bob").toString()), chat(room, "host").get("mutedUsers"));
    }

    @Test
    void theHostPinsATopicEveryoneSeesAndClearsItAgain() {
        String room = roomWithPeople();
        assertNull(chat(room, "alice").get("topic"));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> topic(room, "alice", "Tell us about your weekend"));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> topic(room, "host", ""));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> topic(room, "host", "x".repeat(201)));
        assertEquals(HttpStatus.NO_CONTENT, topic(room, "host", "Tell us about your weekend").status());

        assertEquals("Tell us about your weekend", chat(room, "bob").get("topic"), "a newcomer sees it at once");
        assertEquals("Tell us about your weekend", events(ChatEventType.TOPIC_CHANGED).get(0).topic());

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", () -> delete(room, "/comments/topic", "alice"));
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/topic", "host").status());
        assertNull(chat(room, "bob").get("topic"));
        assertNull(events(ChatEventType.TOPIC_CHANGED).get(1).topic(), "an event with no topic is the topic going away");
    }

    @Test
    void closingTheRoomClearsItsChat() {
        String room = roomWithPeople();
        comment(room, "alice", text("goodbye"));
        topic(room, "host", "Last topic");
        postEmpty(room, "/comments/mute/" + id("bob"), "host");

        delete(room, "", "host");

        String[] keys = {"comments", "topic", "chat-muted"};
        await("the chat is cleared after the answer, off the request", 5_000, () -> {
            for (String key : keys) {
                if (redis.sync().exists("voiceroom:{" + room + "}:" + key) > 0) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void aRoomThatIsGoneHasNoChatToReadOrWriteTo() {
        String room = roomWithPeople();
        delete(room, "", "host");

        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> chat(room, "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "voice-room-not-found", () -> comment(room, "alice", text("anyone?")));
    }

    private io.micronaut.http.HttpResponse<?> topic(String room, String user, String text) {
        return http.exchange(HttpRequest.PUT(path(room, "/comments/topic"), Map.of("text", text)).bearerAuth(token(user)));
    }
}
