package com.jtalk.livecommunication.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The topic the host pins at the top of the room's chat: a question to answer, a word to use. */
@Serdeable
public record PinTopicRequest(@NotBlank @Size(max = 200) String text) {
}
