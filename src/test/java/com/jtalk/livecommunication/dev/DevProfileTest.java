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
        List<String> afterStartup = snapshot();   // nothing has called seed() yet: this is what the startup listener made
        seeder.seed();
        assertEquals(afterStartup, snapshot(), "a second run must not open another room");
    }

    /** One line per dev user: how many active rooms they host, and whether they are seated in the Redis stage. */
    private List<String> snapshot() {
        return DevUser.ALL.stream().map(host -> {
            List<VoiceRoom> hosted = rooms.findAll().stream()
                    .filter(r -> r.hostId().equals(host.id()) && r.status() == VoiceRoomStatus.ACTIVE).toList();
            assertEquals(1, hosted.size(), host.name() + " hosts exactly one active room");
            assertTrue(stage.isOnStage(hosted.getFirst().id(), host.id()), host.name() + " is seated in the Redis stage");
            return host.name() + ":" + hosted.getFirst().id();
        }).toList();
    }

    @Test
    void theTestPageCanLearnWhoTheSeededUsersAre() {
        List<Map<String, Object>> users = http.toBlocking().retrieve(HttpRequest.GET("/dev/users"),
                Argument.listOf(Argument.mapOf(String.class, Object.class)));

        assertEquals(DevUser.ALL.stream().map(u -> u.id().toString()).toList(), users.stream().map(u -> (String) u.get("id")).toList());
    }
}
