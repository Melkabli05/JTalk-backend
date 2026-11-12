package com.jtalk.livecommunication.domain.dto.request;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Serdeable.Deserializable
public record CreateVoiceRoomRequest(
        @NotBlank @Size(max = VoiceRoom.NAME_MAX_LENGTH) String name,
        @Nullable @Min(VoiceRoom.MIN_SPEAKERS) @Max(VoiceRoom.MAX_SPEAKERS) Integer maxSpeakers) {
}
