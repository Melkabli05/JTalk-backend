package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * What the user service announces on {@code user-events}: a profile changed ({@code USER_PROFILE_UPDATED}) or an account is gone
 * ({@code USER_DELETED}). Only the type and the user matter here; anything else in the message is ignored.
 */
@Serdeable.Deserializable
public record UserEvent(@Nullable String type, @Nullable UUID userId) {

    public static final String USER_DELETED = "USER_DELETED";

    public boolean isDeletion() {
        return USER_DELETED.equals(type) && userId != null;
    }
}
