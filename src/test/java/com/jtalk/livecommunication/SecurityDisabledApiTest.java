package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped configuration: no token is needed, and the caller is named by the X-User-Id header. */
@Property(name = "micronaut.security.enabled", value = "false")
class SecurityDisabledApiTest extends ApiTestBase {

    private static final Map<String, Object> BODY = Map.of("title", "Open", "targetLanguage", "en", "nativeLanguage", "ar");

    @Test
    void callsWorkWithoutATokenAndTheHeaderNamesTheHost() {
        HttpResponse<Map<String, Object>> created = http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", BODY).header("X-User-Id", id("host").toString()), MAP);

        assertEquals(HttpStatus.CREATED, created.status());
        assertEquals(id("host").toString(), ((Map<?, ?>) created.body().get("room")).get("hostId"), "the header user became the host");
    }

    @Test
    void theDeveloperTestPageIsServed() {
        HttpResponse<String> page = http.exchange(HttpRequest.GET("/ui/index.html"), String.class);

        assertEquals(HttpStatus.OK, page.status());
        assertTrue(page.body().contains("Live Communication Tester"));
    }

    @Test
    void aCallWithoutAUserIdIsABadRequestNotAServerError() {
        var request = HttpRequest.POST("/api/v1/voice-rooms", BODY);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientResponseException.class, () -> http.exchange(request, MAP)).getStatus());

        var malformed = HttpRequest.POST("/api/v1/voice-rooms", BODY).header("X-User-Id", "not-a-uuid");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientResponseException.class, () -> http.exchange(malformed, MAP)).getStatus());
    }
}
