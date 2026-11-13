package com.jtalk.livecommunication.support;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shared plumbing for end-to-end tests: real PostgreSQL + Redis (Test Resources) and a fake LiveKit. Users are written as
 * names ("host", "alice"); {@link #id(String)} turns each into a stable UUID, which is what the API works with.
 */
@MicronautTest
public abstract class ApiTestBase {

    private static final String JWT_SECRET = "test-jwt-secret-test-jwt-secret-0123456789";

    protected static final Argument<Map<String, Object>> MAP = Argument.mapOf(String.class, Object.class);
    protected static final Argument<List<Map<String, Object>>> MAP_LIST = Argument.listOf(MAP);

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    protected FakeLiveKitGateway gateway;

    protected BlockingHttpClient http;

    @BeforeEach
    void setUpClient() {
        http = httpClient.toBlocking();
        gateway.reset();
    }

    // ---- identities -------------------------------------------------------------------------------

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    protected static UUID uuid(String id) {
        return UUID.fromString(id);
    }

    protected static List<String> ids(String... names) {
        return java.util.Arrays.stream(names).map(n -> id(n).toString()).toList();
    }

    /** A bearer token for this user, signed with the secret from application-test.yml. */
    public static String token(String userName) {
        return token(userName, JWT_SECRET);
    }

    public static String token(String userName, String secret) {
        return tokenWithSubject(id(userName).toString(), secret);
    }

    /** A correctly signed token whose subject is whatever the test needs, e.g. one that is not a UUID. */
    public static String tokenWithSubject(String subject, String secret) {
        return JWT.create()
                .withSubject(subject)
                .withExpiresAt(new Date(System.currentTimeMillis() + 3_600_000))
                .sign(Algorithm.HMAC256(secret));
    }

    // ---- requests ---------------------------------------------------------------------------------

    protected String createRoom(String hostName, String title, Integer maxStageSeats) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", title, "targetLanguage", "en", "nativeLanguage", "ar"));
        if (maxStageSeats != null) {
            body.put("maxStageSeats", maxStageSeats);
        }
        return (String) createRoom(hostName, body).get("id");
    }

    /** Creates a room and returns its {@code room} part of the session; asserts the 201 and Location header. */
    protected Map<String, Object> createRoom(String hostName, Map<String, Object> body) {
        HttpResponse<Map<String, Object>> response = http.exchange(
                HttpRequest.POST("/api/v1/voice-rooms", body).bearerAuth(token(hostName)), MAP);
        assertEquals(HttpStatus.CREATED, response.status());
        assertNotNull(response.header("Location"));
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) response.body().get("room");
        return room;
    }

    protected static String path(String roomId, String suffix) {
        return "/api/v1/voice-rooms/" + roomId + suffix;
    }

    protected Map<String, Object> post(String roomId, String suffix, String userName) {
        return http.retrieve(HttpRequest.POST(path(roomId, suffix), "").bearerAuth(token(userName)), MAP);
    }

    protected HttpResponse<?> postJson(String roomId, String suffix, String userName, Object body) {
        return http.exchange(HttpRequest.POST(path(roomId, suffix), body).bearerAuth(token(userName)));
    }

    protected HttpResponse<?> send(HttpRequest<?> request) {
        return http.exchange(request);
    }

    protected HttpResponse<?> postEmpty(String roomId, String suffix, String userName) {
        return http.exchange(HttpRequest.POST(path(roomId, suffix), "").bearerAuth(token(userName)));
    }

    protected HttpResponse<?> delete(String roomId, String suffix, String userName) {
        return http.exchange(HttpRequest.DELETE(path(roomId, suffix)).bearerAuth(token(userName)));
    }

    protected Map<String, Object> join(String roomId, String userName) {
        return post(roomId, "/participants/join", userName);
    }

    /** A user takes a free stage seat themselves: join the room, then claim. */
    protected void seatSpeaker(String roomId, String userName) {
        join(roomId, userName);
        post(roomId, "/stage/join", userName);
    }

    protected Map<String, Object> getStage(String roomId) {
        return http.retrieve(HttpRequest.GET(path(roomId, "/stage")), MAP);
    }

    protected Map<String, Object> getRoom(String roomId) {
        return http.retrieve(HttpRequest.GET(path(roomId, "")), MAP);
    }

    protected List<Map<String, Object>> getParticipants(String roomId) {
        return http.retrieve(HttpRequest.GET(path(roomId, "/participants")), MAP_LIST);
    }

    /** The rooms on one discovery page; {@code query} is e.g. {@code "?size=2"}. */
    protected List<Map<String, Object>> listRooms(String query) {
        Map<String, Object> page = http.retrieve(HttpRequest.GET("/api/v1/voice-rooms" + query), MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        return content;
    }

    // ---- assertions -------------------------------------------------------------------------------

    /** @param code expected problem code, or null to only check status and content type */
    protected Map<String, Object> assertProblem(HttpStatus status, String code, Runnable call) {
        var ex = assertThrows(HttpClientResponseException.class, call::run);
        assertEquals(status, ex.getStatus());
        assertEquals("application/problem+json", ex.getResponse().getContentType().orElseThrow().toString());
        Map<String, Object> problem = ex.getResponse().getBody(MAP).orElseThrow();
        if (code != null) {
            assertEquals("urn:jtalk:live-communication:problem:" + code, problem.get("type"), problem.toString());
        }
        assertEquals(status.getCode(), problem.get("status"));
        return problem;
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
}
