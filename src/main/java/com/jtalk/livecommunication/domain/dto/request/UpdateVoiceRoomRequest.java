package com.jtalk.livecommunication.domain.dto.request;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: omitted fields keep their value. */
@Serdeable.Deserializable
public record UpdateVoiceRoomRequest(
        @Nullable @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = VoiceRoom.NAME_MAX_LENGTH) String name,
        @Nullable @Min(VoiceRoom.MIN_SPEAKERS) @Max(VoiceRoom.MAX_SPEAKERS) Integer maxSpeakers) {
}
