package com.jtalk.livecommunication;

import com.jtalk.livecommunication.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A browser app on another origin can call the API: it asks first (a preflight, which carries no token), then calls. */
class CorsTest extends ApiTestBase {

    private static final String ORIGIN = "https://frontend.example.test";

    @Test
    void answersThePreflightWithoutAToken() {
        HttpResponse<?> preflight = http.exchange(HttpRequest.OPTIONS("/api/v1/voice-rooms")
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"));

        assertEquals(HttpStatus.OK, preflight.status());
        assertNotNull(preflight.getHeaders().get("Access-Control-Allow-Origin"));
        String allowed = String.join(",", preflight.getHeaders().getAll("Access-Control-Allow-Headers")).toLowerCase();
        assertTrue(allowed.contains("authorization"), allowed);
    }

    @Test
    void letsTheBrowserReadTheAnswerAnywhereItIsGiven() {
        // Without the header a browser hides the response from the app, even an error it needs to react to (a 401 means "sign in").
        for (String uri : new String[] {"/api/v1/voice-rooms", "/api/v1/voice-rooms/" + java.util.UUID.randomUUID()}) {
            HttpResponse<?> response;
            try {
                response = http.exchange(HttpRequest.GET(uri).header("Origin", ORIGIN));
            } catch (HttpClientResponseException e) {
                response = e.getResponse();
            }
            assertEquals(ORIGIN, response.getHeaders().get("Access-Control-Allow-Origin"), uri + " -> " + response.status());
        }
    }
}
