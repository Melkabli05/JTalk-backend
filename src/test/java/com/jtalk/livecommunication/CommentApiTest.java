package com.jtalk.livecommunication;

import com.jtalk.livecommunication.domain.enums.ChatEventType;
import com.jtalk.livecommunication.domain.enums.Reaction;
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
        properties.put("voice-room.reaction-interval", "0s");   // the throttle is the room reactions' own, not what these test
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
    @SuppressWarnings("unchecked")
    void aNewcomerGetsTheLastFiftyCommentsOldestFirstAndNoMore() {
        String room = roomWithPeople();
        for (int i = 1; i <= 55; i++) {
            comment(room, "alice", text("comment " + i));
        }

        List<Map<String, Object>> history = historyOf(room, "bob");

        assertEquals(50, history.size(), "the chat keeps a short tail, and costs the database nothing");
        assertEquals("comment 6", history.get(0).get("content"));
        assertEquals("comment 55", history.get(49).get("content"));
        assertEquals(HttpStatus.NO_CONTENT, topic(room, "host", "Travel").status());
        var snapshot = (Map<String, Object>) join(room, "bob").get("snapshot");
        var bundledChat = (Map<String, Object>) snapshot.get("chat");
        assertEquals(history, bundledChat.get("comments"));
        assertEquals("Travel", bundledChat.get("topic"));
    }

    @Test
    void onlyTheHostDeletesCommentsAndOnlyThatCommentGoes() {
        String room = roomWithPeople();
        String first = (String) comment(room, "alice", text("first")).get("id");
        String reply = (String) comment(room, "bob", Map.of("type", "TEXT", "content", "an answer", "replyTo", first)).get("id");
        gateway.chatEvents.clear();

        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> delete(room, "/comments/" + first, "alice"));
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

        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> postEmpty(room, "/comments/mute/" + id("bob"), "alice"));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-mute-host", () -> postEmpty(room, "/comments/mute/" + id("host"), "host"));
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(room, "/comments/mute/" + id("alice"), "host").status());

        assertEquals(true, join(room, "alice").get("isChatMuted"));
        assertEquals(false, join(room, "bob").get("isChatMuted"));
        assertEquals(HttpStatus.NO_CONTENT, postEmpty(room, "/stage/promote/" + id("alice"), "host").status());
        assertEquals(true, join(room, "alice").get("isChatMuted"));
        assertProblem(HttpStatus.FORBIDDEN, "chat-muted", () -> comment(room, "alice", text("after")));
        assertEquals(true, chat(room, "alice").get("muted"), "they are told when they open the chat, so it can say so");
        assertEquals(false, chat(room, "bob").get("muted"));
        assertEquals(id("alice"), events(ChatEventType.CHAT_MUTED).get(0).targetUserId());
        assertEquals("bob can", comment(room, "bob", text("bob can")).get("content"), "muting one person leaves the others");

        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> delete(room, "/comments/mute/" + id("alice"), "bob"));
        assertEquals(HttpStatus.NO_CONTENT, delete(room, "/comments/mute/" + id("alice"), "host").status());
        assertEquals(false, join(room, "alice").get("isChatMuted"));
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

        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> topic(room, "alice", "Tell us about your weekend"));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> topic(room, "host", ""));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> topic(room, "host", "x".repeat(201)));
        assertEquals(HttpStatus.NO_CONTENT, topic(room, "host", "Tell us about your weekend").status());

        assertEquals("Tell us about your weekend", chat(room, "bob").get("topic"), "a newcomer sees it at once");
        assertEquals("Tell us about your weekend", events(ChatEventType.TOPIC_CHANGED).get(0).topic());

        assertProblem(HttpStatus.FORBIDDEN, "not-room-moderator", () -> delete(room, "/comments/topic", "alice"));
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
        react(room, comment(room, "alice", text("Reacted to")).get("id"), "alice", "CLAP");

        delete(room, "", "host");

        String[] keys = {"comments", "topic", "chat-muted", "reaction-counts"};
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

    private HttpStatus react(String room, Object commentId, String user, String reaction) {
        return http.exchange(HttpRequest.PUT(path(room, "/comments/" + commentId + "/reaction"), Map.of("reaction", reaction)).bearerAuth(token(user)))
                .status();
    }

    private HttpStatus unreact(String room, Object commentId, String user) {
        return http.exchange(HttpRequest.DELETE(path(room, "/comments/" + commentId + "/reaction")).bearerAuth(token(user))).status();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reactionsTo(String room, String user, Object commentId) {
        return ((List<Map<String, Object>>) chat(room, user).get("reactions")).stream()
                .filter(entry -> commentId.equals(entry.get("commentId"))).findFirst().orElse(null);
    }

    @Test
    void peopleReactToAComment_oneEach_theRoomIsToldAndTheCountsAreWhatTheyOpenTheChatTo() {
        String room = roomWithPeople();
        Object id = comment(room, "alice", text("Love this")).get("id");

        assertEquals(HttpStatus.NO_CONTENT, react(room, id, "bob", "CLAP"));
        assertEquals(HttpStatus.NO_CONTENT, react(room, id, "host", "CLAP"));
        assertEquals(HttpStatus.NO_CONTENT, react(room, id, "alice", "FIRE"));

        var told = events(ChatEventType.COMMENT_REACTED);
        assertEquals(3, told.size());
        assertEquals(id("bob"), told.get(0).targetUserId());
        assertEquals(Reaction.CLAP, told.get(0).reaction());
        assertNull(told.get(0).previous());

        Map<String, Object> asBob = reactionsTo(room, "bob", id);
        assertEquals(Map.of("CLAP", 2, "FIRE", 1), asBob.get("counts"));
        assertEquals("CLAP", asBob.get("mine"), "each person is told which one is theirs");
        assertEquals("FIRE", reactionsTo(room, "alice", id).get("mine"));
        assertNull(reactionsTo(room, "bob", comment(room, "bob", text("Nobody reacted")).get("id")), "a comment nobody reacted to has no entry");
    }

    @Test
    void aNewReactionReplacesThePersonsOldOne_andTakingItBackRemovesIt() {
        String room = roomWithPeople();
        Object id = comment(room, "alice", text("Hola")).get("id");

        react(room, id, "bob", "CLAP");
        react(room, id, "bob", "HEART");
        assertEquals(Map.of("HEART", 1), reactionsTo(room, "bob", id).get("counts"));
        var changed = events(ChatEventType.COMMENT_REACTED).get(1);
        assertEquals(Reaction.HEART, changed.reaction());
        assertEquals(Reaction.CLAP, changed.previous(), "the event says what it replaced, so a client can move the count");

        assertEquals(HttpStatus.NO_CONTENT, unreact(room, id, "bob"));
        assertNull(reactionsTo(room, "bob", id));
        var removed = events(ChatEventType.COMMENT_REACTED).get(2);
        assertNull(removed.reaction());
        assertEquals(Reaction.HEART, removed.previous());
    }

    @Test
    void reactingTheSameWayAgainOrTakingBackNothingChangesNothingAndTellsNoOne() {
        String room = roomWithPeople();
        Object id = comment(room, "alice", text("Hola")).get("id");

        react(room, id, "bob", "CLAP");
        assertEquals(HttpStatus.NO_CONTENT, react(room, id, "bob", "CLAP"));
        assertEquals(HttpStatus.NO_CONTENT, unreact(room, id, "alice"));

        assertEquals(1, events(ChatEventType.COMMENT_REACTED).size());
        assertEquals(Map.of("CLAP", 1), reactionsTo(room, "bob", id).get("counts"));
    }

    @Test
    void onlyAPersonInTheRoom_whoIsNotMuted_reactsToAComment_thatIsStillThere() {
        String room = roomWithPeople();
        Object id = comment(room, "alice", text("Hola")).get("id");

        assertProblem(HttpStatus.FORBIDDEN, "not-in-room", () -> react(room, id, "stranger", "CLAP"));
        assertProblem(HttpStatus.NOT_FOUND, "comment-not-found", () -> react(room, UUID.randomUUID(), "bob", "CLAP"));
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> react(room, id, "bob", "NOT_A_REACTION"));
        postEmpty(room, "/comments/mute/" + id("bob"), "host");
        assertProblem(HttpStatus.FORBIDDEN, "chat-muted", () -> react(room, id, "bob", "CLAP"));
        assertEquals(List.of(), events(ChatEventType.COMMENT_REACTED));
    }

    @Test
    void aDeletedCommentTakesItsReactionsWithIt() {
        String room = roomWithPeople();
        Object id = comment(room, "alice", text("Oops")).get("id");
        react(room, id, "bob", "CLAP");

        delete(room, "/comments/" + id, "host");

        assertEquals(List.of(), redis.sync().hkeys("voiceroom:{" + room + "}:reaction-counts"), "its counts go with it");
        assertProblem(HttpStatus.NOT_FOUND, "comment-not-found", () -> react(room, id, "bob", "CLAP"));
    }

    @Test
    void rejectsImagePasteOrInjectionInComments() {
        String room = roomWithPeople();
        for (String payload : List.of(
                "data:image/png;base64,iVBORw0KGgo=",
                "<img src=\"https://example.com/photo.png\">",
                "![photo](https://example.com/photo.jpg)",
                "https://cdn.example.com/pic.webp?size=large")) {
            assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment",
                    () -> comment(room, "alice", text(payload)));
        }
    }

    @Test
    void gifCommentsRequireApprovedProviderUrl() {
        String room = roomWithPeople();
        String approved = "https://media.giphy.com/media/111ebonMs90YLu/giphy.gif";
        Map<String, Object> posted = comment(room, "alice", Map.of("type", "GIF", "content", approved));
        assertEquals("GIF", posted.get("type"));
        assertEquals(approved, posted.get("content"));

        assertProblem(HttpStatus.BAD_REQUEST, "invalid-comment",
                () -> comment(room, "alice", Map.of("type", "GIF", "content", "https://evil.example.com/test.gif")));
    }
}
