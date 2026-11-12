package com.jtalk.livecommunication.domain.event;

import java.util.UUID;

/**
 * Published when a room is closed so other modules can drop their ephemeral state without
 * the voiceroom module having to know they exist.
 */
public record VoiceRoomClosed(UUID roomId) {
}
