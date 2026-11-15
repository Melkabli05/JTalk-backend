package com.jtalk.livecommunication.service;

/** Verifies and applies LiveKit webhook events. */
public interface LiveKitWebhookService {

    /** @param authorizationHeader the signed JWT LiveKit sends in the {@code Authorization} header */
    void handle(String body, String authorizationHeader);
}
