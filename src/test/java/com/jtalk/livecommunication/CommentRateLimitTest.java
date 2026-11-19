package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How much one person may comment: three in five seconds by default, counted for each person in each room. */
class CommentRateLimitTest extends ApiTestBase {

    private void comment(String room, String user, String text) {
        http.exchange(HttpRequest.POST(path(room, "/comments"), Map.of("type", "TEXT", "content", text)).bearerAuth(token(user)));
    }

    private int commentsIn(String room) {
        Map<String, Object> chat = http.retrieve(HttpRequest.GET(path(room, "/comments")).bearerAuth(token("bob")), MAP);
        return ((java.util.List<?>) chat.get("comments")).size();
    }

    @Test
    void aFourthCommentInTheWindowIsTurnedAwayAndSomeoneElseIsNotAffected() {
        String room = createRoom("host", "Busy", 4);
        join(room, "host");
        join(room, "alice");
        join(room, "bob");

        for (int i = 1; i <= 3; i++) {
            comment(room, "alice", "message " + i);
        }

        assertProblem(HttpStatus.TOO_MANY_REQUESTS, "commenting-too-fast", () -> comment(room, "alice", "message 4"));
        comment(room, "bob", "bob is not slowed down by alice");
        assertEquals(4, commentsIn(room), "three of alice's and bob's, and none of the refused one");
    }

    @Test
    void thePerRoomCountsAreSeparate() {
        String first = createRoom("host", "One", 4);
        String second = createRoom("host2", "Two", 4);
        join(first, "alice");
        join(second, "alice");
        for (int i = 1; i <= 3; i++) {
            comment(first, "alice", "message " + i);
        }

        comment(second, "alice", "a fresh room, a fresh count");

        assertProblem(HttpStatus.TOO_MANY_REQUESTS, "commenting-too-fast", () -> comment(first, "alice", "message 4"));
    }
}
