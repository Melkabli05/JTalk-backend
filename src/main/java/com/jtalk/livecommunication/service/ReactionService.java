package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.enums.Reaction;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;

import java.util.UUID;

/** What people in a room show without speaking. Reactions are not stored: they are told to the room and gone. */
@Validated
public interface ReactionService {

    /**
     * Tells the room that the user reacted. 404 when the room is not active, 403 {@code not-in-room} unless the user has joined it,
     * 429 {@code reacting-too-fast} when they reacted a moment ago.
     */
    void react(@NonNull UUID roomId, @NonNull UUID userId, @NonNull Reaction reaction);
}
