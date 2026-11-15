package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** What a load balancer or orchestrator needs: probes that answer without a token, saying only whether the service is up. */
class HealthEndpointTest extends ApiTestBase {

    @Test
    void healthAnswersWithoutAToken() {
        var response = http.exchange(HttpRequest.GET("/health"), MAP);

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("UP", response.body().get("status"));
    }

    @Test
    void livenessAndReadinessAnswerWithoutAToken() {
        assertEquals(HttpStatus.OK, http.exchange(HttpRequest.GET("/health/liveness"), MAP).status());
        assertEquals(HttpStatus.OK, http.exchange(HttpRequest.GET("/health/readiness"), MAP).status());
    }

    @Test
    void healthKeepsItsDetailsFromAnonymousCallers() {
        var body = http.exchange(HttpRequest.GET("/health"), MAP).body();

        assertFalse(body.containsKey("details"), "database and Redis details are for authenticated callers only");
    }
}
