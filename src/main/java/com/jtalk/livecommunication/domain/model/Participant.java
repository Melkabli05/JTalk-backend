package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;

import java.util.UUID;

public record Participant(UUID userId, ParticipantRole role, PresenceStatus status) {
}
