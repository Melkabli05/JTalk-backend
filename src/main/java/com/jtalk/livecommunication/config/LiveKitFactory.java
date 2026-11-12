package com.jtalk.livecommunication.config;

import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** The LiveKit SDK clients as injectable beans, built once from {@link LiveKitProperties}. */
@Factory
class LiveKitFactory {

    @Singleton
    RoomServiceClient roomServiceClient(LiveKitProperties properties) {
        return RoomServiceClient.createClient(properties.apiUrl(), properties.apiKey(), properties.apiSecret());
    }

    @Singleton
    WebhookReceiver webhookReceiver(LiveKitProperties properties) {
        return new WebhookReceiver(properties.apiKey(), properties.apiSecret());
    }
}
