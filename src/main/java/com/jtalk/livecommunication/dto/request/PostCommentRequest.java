package com.jtalk.livecommunication.dto.request;

import com.jtalk.livecommunication.domain.enums.CommentType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A comment to post. A {@code CORRECTION} also needs {@code correctedContent}, what it should have been; a {@code TEXT} must not have one.
 *
 * @param replyTo the comment this answers, if any
 */
@Serdeable
public record PostCommentRequest(
        @NotNull CommentType type,
        @NotBlank @Size(max = 500) String content,
        @Nullable @Size(max = 500) String correctedContent,
        @Nullable UUID replyTo) {
}
