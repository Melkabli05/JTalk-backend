package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record RoomJoinSnapshot(List<ParticipantPreview> speakers, long audienceCount, AudiencePage audience,
                               List<UUID> handRaiseQueue, ChatState chat) {
}
