package com.jtalk.livecommunication.dev;

import io.micronaut.context.BeanContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The dev profile as it ships: no sample rooms or people, only those who register themselves. */
@MicronautTest(environments = "dev")
class DevProfileWithoutSeedingTest {

    @Inject
    BeanContext context;

    @Inject
    @Client("/")
    HttpClient http;

    private List<Map<String, Object>> users() {
        return http.toBlocking().retrieve(HttpRequest.GET("/dev/users"), Argument.listOf(Argument.mapOf(String.class, Object.class)));
    }

    private HttpStatus register(Map<String, String> body) {
        return http.toBlocking().exchange(HttpRequest.POST("/dev/users", body)).status();
    }

    @Test
    void noRoomsAreSeededUnlessAskedFor() {
        assertFalse(context.containsBean(DevDataSeeder.class));
    }

    @Test
    void aClientRegistersItsPersonAndEveryoneCanThenReadTheirName() {
        UUID id = UUID.randomUUID();

        assertEquals(HttpStatus.NO_CONTENT, register(Map.of("id", id.toString(), "name", "  Mona  ", "nativeLanguage", "ar", "learning", "en", "avatar", "avatar-04")));

        assertEquals(List.of(Map.of("id", id.toString(), "name", "Mona", "nativeLanguage", "ar", "learning", "en", "avatar", "avatar-04")),
                users().stream().filter(u -> id.toString().equals(u.get("id"))).toList());
    }

    @Test
    void registeringAgainReplacesThePerson() {
        UUID id = UUID.randomUUID();
        register(Map.of("id", id.toString(), "name", "Old", "nativeLanguage", "ar", "learning", "en"));
        register(Map.of("id", id.toString(), "name", "New", "nativeLanguage", "fr", "learning", "de"));

        assertEquals(List.of("New"), users().stream().filter(u -> id.toString().equals(u.get("id"))).map(u -> u.get("name")).toList());
    }

    @Test
    void thePictureIsOptional() {
        UUID id = UUID.randomUUID();
        register(Map.of("id", id.toString(), "name", "Sami", "nativeLanguage", "ar", "learning", "en"));

        assertEquals(List.of(""), users().stream().filter(u -> id.toString().equals(u.get("id"))).map(u -> u.get("avatar")).toList());
    }

    @Test
    void aProfileThatIsNotUsableIsRefused() {
        var bad = Map.of("id", UUID.randomUUID().toString(), "name", "   ", "nativeLanguage", "ar", "learning", "en");

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientResponseException.class, () -> register(bad)).getStatus());
    }

    @Test
    void theSampleUsersAreNotListedWhenThereAreNoSampleRooms() {
        assertFalse(users().stream().anyMatch(u -> DevUser.ALL.stream().anyMatch(s -> s.id().toString().equals(u.get("id")))));
    }
}
