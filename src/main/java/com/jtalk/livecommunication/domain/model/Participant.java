package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;

public record Participant(String userId, ParticipantRole role, PresenceStatus status) {
}
