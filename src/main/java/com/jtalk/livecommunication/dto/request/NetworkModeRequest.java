package com.jtalk.livecommunication.dto.request;

import com.jtalk.livecommunication.domain.enums.NetworkMode;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param secondaryTrackSidsToDrop tracks to unsubscribe from in {@code LOW_BANDWIDTH} mode (and to restore in {@code OPTIMAL})
 * @param primaryTrackSidsToKeep   tracks to stay subscribed to
 */
@Serdeable.Deserializable
public record NetworkModeRequest(
        @NotNull NetworkMode mode,
        @Nullable @Size(max = 20) List<@NotBlank @Size(max = 64) String> secondaryTrackSidsToDrop,
        @Nullable @Size(max = 20) List<@NotBlank @Size(max = 64) String> primaryTrackSidsToKeep) {

    public NetworkModeRequest {
        secondaryTrackSidsToDrop = secondaryTrackSidsToDrop == null ? List.of() : List.copyOf(secondaryTrackSidsToDrop);
        primaryTrackSidsToKeep = primaryTrackSidsToKeep == null ? List.of() : List.copyOf(primaryTrackSidsToKeep);
    }
}
