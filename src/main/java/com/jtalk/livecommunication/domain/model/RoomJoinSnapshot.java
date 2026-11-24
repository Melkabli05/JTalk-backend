package com.jtalk.livecommunication.domain.model;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

@Serdeable
public record RoomJoinSnapshot(List<ParticipantPreview> speakers, long audienceCount, AudiencePage audience,
                               List<UUID> handRaiseQueue, ChatState chat) {
}
