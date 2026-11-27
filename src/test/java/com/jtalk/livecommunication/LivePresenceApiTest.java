package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePresenceApiTest extends ApiTestBase {

    @Test
    void reportsSingleAndBatchLiveRoomPresence() {
        String roomId = createRoom("host", "Spanish Practice", 6);
        post(roomId, "/participants/join", "host");
        post(roomId, "/participants/join", "guest");

        Map<String, Object> hostPresence = http.retrieve(
                HttpRequest.GET("/api/v1/voice-rooms/presence/" + id("host")).bearerAuth(token("viewer")), MAP);
        assertEquals(id("host").toString(), hostPresence.get("userId"));
        assertTrue((Boolean) hostPresence.get("live"));
        assertEquals(roomId, hostPresence.get("roomId"));

        Map<String, Object> offlinePresence = http.retrieve(
                HttpRequest.GET("/api/v1/voice-rooms/presence/" + id("offline")).bearerAuth(token("viewer")), MAP);
        assertFalse((Boolean) offlinePresence.get("live"));
        assertNull(offlinePresence.get("roomId"));

        List<Map<String, Object>> batch = http.retrieve(
                HttpRequest.POST("/api/v1/voice-rooms/presence/batch",
                                Map.of("userIds", List.of(id("host"), id("guest"), id("offline"))))
                        .bearerAuth(token("viewer")),
                MAP_LIST);
        assertEquals(3, batch.size());
        assertTrue((Boolean) batch.get(0).get("live"));
        assertEquals(roomId, batch.get(0).get("roomId"));
        assertTrue((Boolean) batch.get(1).get("live"));
        assertEquals(roomId, batch.get(1).get("roomId"));
        assertFalse((Boolean) batch.get(2).get("live"));
        assertNull(batch.get(2).get("roomId"));

        postEmpty(roomId, "/participants/leave", "guest");
        Map<String, Object> afterLeave = http.retrieve(
                HttpRequest.GET("/api/v1/voice-rooms/presence/" + id("guest")).bearerAuth(token("viewer")), MAP);
        assertFalse((Boolean) afterLeave.get("live"));
        assertNull(afterLeave.get("roomId"));
    }
}
