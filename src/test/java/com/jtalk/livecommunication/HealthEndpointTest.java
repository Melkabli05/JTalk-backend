package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a load balancer or orchestrator needs: probes that answer without a token, saying only whether the service is up.
 * The shared test client adds a token to every GET, so these probes use a plain client that does not (a real probe has none).
 */
class HealthEndpointTest extends ApiTestBase {

    @Inject
    EmbeddedServer server;

    private HttpClient rawClient;
    private BlockingHttpClient probe;

    @BeforeEach
    void openProbe() {
        rawClient = HttpClient.create(server.getURL());
        probe = rawClient.toBlocking();
    }

    @AfterEach
    void closeProbe() {
        rawClient.close();
    }

    @Test
    void healthAnswersWithoutAToken() {
        var response = probe.exchange(HttpRequest.GET("/health"), MAP);

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("UP", response.body().get("status"));
    }

    @Test
    void livenessAndReadinessAnswerWithoutAToken() {
        assertEquals(HttpStatus.OK, probe.exchange(HttpRequest.GET("/health/liveness"), MAP).status());
        assertEquals(HttpStatus.OK, probe.exchange(HttpRequest.GET("/health/readiness"), MAP).status());
    }

    @Test
    void healthKeepsItsDetailsFromAnonymousCallers() {
        var body = probe.exchange(HttpRequest.GET("/health"), MAP).body();

        assertFalse(body.containsKey("details"), "database and Redis details are for authenticated callers only, got: " + body);
    }

    @Test
    void healthShowsItsDetailsToAuthenticatedCallers() {
        var body = http.exchange(HttpRequest.GET("/health"), MAP).body();   // the shared client sends a token

        assertTrue(body.containsKey("details"), "an authenticated caller should see what is behind the status, got: " + body);
    }
}
