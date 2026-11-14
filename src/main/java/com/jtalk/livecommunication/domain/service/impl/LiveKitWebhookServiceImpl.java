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
        // Not one of our voice rooms or not one of our users: nothing to do.
        MediaParticipant.of(event).ifPresent(who -> {
            switch (event.getEvent()) {
                case "participant_left" -> participants.handleMediaDisconnected(who.roomId(), who.userId());
                case "participant_joined" -> participants.handleMediaReconnected(who.roomId(), who.userId());
                default -> { /* other events are not needed */ }
            }
        });
    }

    private WebhookEvent verify(String body, String authorizationHeader) {
        try {
            return receiver.receive(body, authorizationHeader);
        } catch (Exception e) {
            throw ProblemType.INVALID_WEBHOOK_SIGNATURE.because("Invalid LiveKit webhook signature");
        }
    }

    /** The room and user a LiveKit event is about, if both are ours: LiveKit names rooms and identities with our UUIDs. */
    private record MediaParticipant(UUID roomId, UUID userId) {

        static Optional<MediaParticipant> of(WebhookEvent event) {
            return parseId(event.getRoom().getName())
                    .flatMap(roomId -> parseId(event.getParticipant().getIdentity()).map(userId -> new MediaParticipant(roomId, userId)));
        }

        private static Optional<UUID> parseId(String value) {
            try {
                return Optional.of(UUID.fromString(value));
            } catch (IllegalArgumentException _) {
                return Optional.empty();
            }
        }
    }
}
