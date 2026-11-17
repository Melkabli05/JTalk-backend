package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.RedisCommandRecorder;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the optimizations on the hot paths by counting the Redis commands a request really issues, so a regression
 * (say, a lookup per participant) fails a test instead of showing up as latency under load.
 */
class RedisCommandBudgetTest extends ApiTestBase {

    @Inject
    RedisCommandRecorder redis;

    @Test
    void theParticipantListCostsTheSameRedisCommandsHoweverManyPeopleAreInTheRoom() {
        String roomId = createRoom("host", "Budget list", 3);
        join(roomId, "host");
        join(roomId, "user-0");

        List<String> few = redis.during(() -> getParticipants(roomId));
        for (int i = 1; i <= 15; i++) {
            join(roomId, "user-" + i);
        }
        List<String> many = redis.during(() -> getParticipants(roomId));

        assertFalse(few.isEmpty(), "the recorder must actually see commands, or this test proves nothing");
        assertEquals(few, many, "no lookup per participant: lease checks are one MGET for everyone");
        assertEquals(16 + 1, getParticipants(roomId).size());
    }

    @Test
    void discoveryReadsLiveNumbersWithThreeCommandsPerRoomAndNothingElse() {
        for (String title : List.of("budget-a", "budget-b", "budget-c")) {
            createRoom("host-" + title, java.util.Map.of("title", title, "targetLanguage", "bu", "nativeLanguage", "en"));
        }

        List<String> commands = redis.during(() -> listRooms("?targetLanguage=bu&size=50"));

        assertEquals(3 * 3, commands.size(), "ZRANGE + ZCARD + ZCOUNT per room, pipelined, no per-room round trips beyond that");
        assertEquals(HttpStatus.OK, http.exchange(io.micronaut.http.HttpRequest.GET("/api/v1/voice-rooms?targetLanguage=bu")).status());
    }
}
