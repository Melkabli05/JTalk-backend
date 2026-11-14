package com.jtalk.livecommunication.dev;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.repository.VoiceRoomRepository;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the real "dev" profile: the mock data must exist, must be consistent across stores, and must not duplicate. */
@MicronautTest(environments = "dev")
class DevProfileTest {

    private static final List<DevUser> HOSTS = List.of(DevUser.ALICE, DevUser.BOB, DevUser.CAROL, DevUser.DINA, DevUser.YUKI, DevUser.HANS, DevUser.SOFIA);

    @Inject
    DevDataSeeder seeder;

    @Inject
    VoiceRoomRepository rooms;

    @Inject
    StageRepository stage;

    @Inject
    @Client("/")
    HttpClient http;

    @Test
    void startupSeedsOneSeatedRoomPerDevUserAndReseedingChangesNothing() {
        seeder.seed();   // waits for the background run started at startup, then repeats it
        List<String> afterFirstRun = snapshot();
        seeder.seed();
        assertEquals(afterFirstRun, snapshot(), "a second run must not open another room");
    }

    /** One line per dev user: how many active rooms they host, and whether they are seated in the Redis stage. */
    private List<String> snapshot() {
        return HOSTS.stream().map(host -> {
            List<VoiceRoom> hosted = rooms.findAll().stream()
                    .filter(r -> r.hostId().equals(host.id()) && r.status() == VoiceRoomStatus.ACTIVE).toList();
            assertEquals(1, hosted.size(), host.name() + " hosts exactly one active room");
            assertTrue(stage.isOnStage(hosted.getFirst().id(), host.id()), host.name() + " is seated in the Redis stage");
            return host.name() + ":" + hosted.getFirst().id();
        }).toList();
    }

    @Test
    void theRoomsAreInTheStatesTheyAreMeantToShow() {
        Map<String, Map<String, Object>> byTitle = new java.util.HashMap<>();
        List<Map<String, Object>> page = (List<Map<String, Object>>) http.toBlocking().retrieve(
                HttpRequest.GET("/api/v1/voice-rooms?size=100").header("X-User-Id", DevUser.ALICE.id().toString()),
                Argument.mapOf(String.class, Object.class)).get("content");
        page.forEach(r -> byTitle.put((String) r.get("title"), r));

        Map<String, Object> full = byTitle.get("Deutsch am Morgen");
        assertEquals(((Number) full.get("maxStageSeats")).intValue(), ((List<?>) full.get("speakers")).size(), "the German room's stage is full");
        assertEquals(2L, ((Number) byTitle.get("English conversation club").get("handRaiseCount")).longValue(), "two hands up in the English club");
        assertTrue(((Number) byTitle.get("Spanish for beginners").get("participantCount")).intValue() >= 6, "Alice's room has an audience: ");
    }

    @Test
    void theTestPageCanLearnWhoTheSeededUsersAre() {
        List<Map<String, Object>> users = http.toBlocking().retrieve(HttpRequest.GET("/dev/users"),
                Argument.listOf(Argument.mapOf(String.class, Object.class)));

        assertEquals(DevUser.ALL.stream().map(u -> u.id().toString()).toList(), users.stream().map(u -> (String) u.get("id")).toList());
    }
}
