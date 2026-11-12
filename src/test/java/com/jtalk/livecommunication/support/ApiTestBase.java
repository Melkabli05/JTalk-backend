package com.jtalk.livecommunication.support;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Shared plumbing for end-to-end tests: real PostgreSQL + Redis (Test Resources) and a fake LiveKit. */
@MicronautTest
public abstract class ApiTestBase {

    protected static final Argument<Map<String, Object>> MAP = Argument.mapOf(String.class, Object.class);
    protected static final Argument<List<Map<String, Object>>> MAP_LIST = Argument.listOf(MAP);

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    protected FakeMediaService media;

    protected BlockingHttpClient http;

    @BeforeEach
    void setUpClient() {
        http = httpClient.toBlocking();
        media.reset();
    }

    protected String createRoom(String hostId, String name, Integer maxSpeakers) {
        Map<String, Object> body = maxSpeakers == null
                ? Map.of("name", name)
                : Map.of("name", name, "maxSpeakers", maxSpeakers);
        HttpResponse<Map<String, Object>> response = http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).header("X-User-Id", hostId), MAP);
        assertEquals(HttpStatus.CREATED, response.status());
        assertNotNull(response.header("Location"));
        return (String) response.body().get("id");
    }

    protected static String path(String roomId, String suffix) {
        return "/api/v1/voice-rooms/" + roomId + suffix;
    }

    protected Map<String, Object> post(String roomId, String suffix, String userId) {
        return http.retrieve(HttpRequest.POST(path(roomId, suffix), "").header("X-User-Id", userId), MAP);
    }

    protected HttpResponse<?> postJson(String roomId, String suffix, String userId, Object body) {
        return http.exchange(HttpRequest.POST(path(roomId, suffix), body).header("X-User-Id", userId));
    }

    protected HttpResponse<?> put(String roomId, String suffix, String userId) {
        return http.exchange(HttpRequest.PUT(path(roomId, suffix), "").header("X-User-Id", userId));
    }

    protected HttpResponse<?> delete(String roomId, String suffix, String userId) {
        return http.exchange(HttpRequest.DELETE(path(roomId, suffix)).header("X-User-Id", userId));
    }

    protected HttpResponse<?> deleteRoom(String roomId, String userId) {
        return http.exchange(HttpRequest.DELETE("/api/v1/voice-rooms/" + roomId).header("X-User-Id", userId));
    }

    protected Map<String, Object> getStage(String roomId) {
        return http.retrieve(HttpRequest.GET(path(roomId, "/stage")), MAP);
    }

    protected List<Map<String, Object>> getParticipants(String roomId) {
        return http.retrieve(HttpRequest.GET(path(roomId, "/participants")), MAP_LIST);
    }

    /** Puts a user on stage the way real clients do: join, raise hand, host approves. */
    protected void seatSpeaker(String roomId, String hostId, String userId) {
        post(roomId, "/participants/me", userId);
        post(roomId, "/stage/hand-raises", userId);
        put(roomId, "/stage/speakers/" + userId, hostId);
    }

    /** @param code expected problem code, or null to only check status and content type */
    protected void assertProblem(HttpStatus status, String code, Runnable call) {
        var ex = assertThrows(HttpClientResponseException.class, call::run);
        assertEquals(status, ex.getStatus());
        assertEquals("application/problem+json", ex.getResponse().getContentType().orElseThrow().toString());
        Map<String, Object> problem = ex.getResponse().getBody(MAP).orElseThrow();
        if (code != null) {
            assertEquals("urn:jtalk:live-communication:problem:" + code, problem.get("type"), problem.toString());
        }
        assertEquals(status.getCode(), problem.get("status"));
    }

    /** Polls until the condition holds, failing after {@code timeoutMillis}. */
    protected static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }

    protected static UUID uuid(String id) {
        return UUID.fromString(id);
    }
}
