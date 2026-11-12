package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.service.LiveKitWebhookService;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageLeaseService;
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
    private final StageLeaseService leases;
    private final ParticipantService participants;

    @Override
    public void handle(String body, String authorizationHeader) {
        WebhookEvent event = verify(body, authorizationHeader);
        Optional<UUID> roomId = parseRoomId(event.getRoom().getName());
        String identity = event.getParticipant().getIdentity();
        if (roomId.isEmpty() || identity.isBlank()) {
            return;
        }
        switch (event.getEvent()) {
            // A speaker keeps their seat under a grace lease; everyone else simply leaves.
            case "participant_left" -> {
                if (!leases.onDisconnect(roomId.get(), identity)) {
                    participants.leave(roomId.get(), identity);
                }
            }
            case "participant_joined" -> leases.onReconnect(roomId.get(), identity);
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

    private static Optional<UUID> parseRoomId(String roomName) {
        try {
            return Optional.of(UUID.fromString(roomName));
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // a LiveKit room that is not one of our voice rooms
        }
    }
}
