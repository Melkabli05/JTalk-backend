package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/**
 * A room's chat as one person sees it when they open it: the last comments, oldest first, the pinned topic if there is one, whether the
 * host has muted them, and, for the host only, everyone they have muted (the rest are told as it happens, and see only their own).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ChatState(List<RoomComment> comments, @Nullable String topic, boolean muted, List<UUID> mutedUsers) {
}
