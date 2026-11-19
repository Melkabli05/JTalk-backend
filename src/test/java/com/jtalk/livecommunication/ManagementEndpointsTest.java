package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's management endpoints (beans, routes, env, loggers, refresh, threaddump, flyway, ...) are off: with only a login they used to
 * reach their handlers for any signed-in user, whatever their role. Only the health probes are exposed (HealthEndpointTest covers those).
 */
class ManagementEndpointsTest extends ApiTestBase {

    private static final String[] PATHS = {"/beans", "/routes", "/loggers", "/env", "/info", "/refresh", "/stop", "/threaddump", "/metrics",
            "/caches", "/flyway", "/heapdump"};

    private HttpStatus statusOf(HttpRequest<?> request) {
        try {
            return http.exchange(request).status();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    @Test
    void aSignedInPersonReachesNoneOfThemWhateverTheMethod() {
        for (String path : PATHS) {
            for (HttpRequest<?> request : new HttpRequest<?>[]{HttpRequest.GET(path).bearerAuth(token("alice")), HttpRequest.POST(path, "").bearerAuth(token("alice"))}) {
                HttpStatus status = statusOf(request);
                assertTrue(status == HttpStatus.FORBIDDEN || status == HttpStatus.NOT_FOUND,
                        request.getMethod() + " " + path + " answered " + status + ": it must not reach a handler (a 200 or a 500 means it did)");
            }
        }
    }

    @Test
    void anonymousCallersAreTurnedAwayAtTheDoor() {
        for (String path : PATHS) {
            HttpStatus status = statusOf(HttpRequest.GET(path).header("Authorization", ""));
            assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.NOT_FOUND, "GET " + path + " answered " + status);
        }
    }

    @Test
    void theProbesStillAnswerWithoutAToken() {
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/health").header("Authorization", "")));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/health/liveness").header("Authorization", "")));
    }
}
