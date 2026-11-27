package com.jtalk.livecommunication.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable.Deserializable
public record LeaveRoomRequest(@Nullable UUID successorUserId) {
}
