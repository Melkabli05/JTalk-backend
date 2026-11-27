package com.jtalk.livecommunication.domain.event;

import java.util.UUID;

/** Published when a solo-room inactivity timer expires. */
public record SoloRoomExpired(UUID roomId) {
}
