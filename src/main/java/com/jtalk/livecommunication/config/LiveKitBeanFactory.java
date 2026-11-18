package com.jtalk.livecommunication.config;

import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import okhttp3.OkHttpClient;

/** The LiveKit SDK clients as injectable beans, built once from {@link LiveKitConfig}. */
@Factory
class LiveKitBeanFactory {

    @Singleton
    RoomServiceClient roomServiceClient(LiveKitConfig properties) {
        return RoomServiceClient.createClient(properties.apiUrl(), properties.apiKey(), properties.apiSecret(), () -> new OkHttpClient.Builder()
                .connectTimeout(properties.requestTimeout().dividedBy(2))
                .callTimeout(properties.requestTimeout())
                .build());
    }

    @Singleton
    WebhookReceiver webhookReceiver(LiveKitConfig properties) {
        return new WebhookReceiver(properties.apiKey(), properties.apiSecret());
    }
}
