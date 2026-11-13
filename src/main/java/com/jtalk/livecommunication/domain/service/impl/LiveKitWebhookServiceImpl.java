package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.service.LiveKitWebhookService;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.livekit.server.WebhookReceiver;
import jakarta.inject.Singleton;
import livekit.LivekitWebhook.WebhookEvent;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class LiveKitWebhookServiceImpl implements LiveKitWebhookService {

    private final WebhookReceiver receiver;
    private final ParticipantService participants;

    @Override
    public void handle(String body, String authorizationHeader) {
        WebhookEvent event = verify(body, authorizationHeader);
        Optional<UUID> roomId = parseId(event.getRoom().getName());
        Optional<UUID> userId = parseId(event.getParticipant().getIdentity());
        if (roomId.isEmpty() || userId.isEmpty()) {
            return;   // not one of our voice rooms (e.g. a whisper's private room) or not one of our users
        }
        switch (event.getEvent()) {
            case "participant_left" -> participants.handleMediaDisconnected(roomId.get(), userId.get());
            case "participant_joined" -> participants.handleMediaReconnected(roomId.get(), userId.get());
            default -> { /* other events are not needed */ }
        }
    }

    private WebhookEvent verify(String body, String authorizationHeader) {
        try {
            return receiver.receive(body, authorizationHeader);
        } catch (Exception e) {
            throw ProblemType.INVALID_WEBHOOK_SIGNATURE.because("Invalid LiveKit webhook signature");
        }
    }

    private static Optional<UUID> parseId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
