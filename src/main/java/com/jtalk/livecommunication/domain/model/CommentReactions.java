package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.Reaction;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;
import java.util.UUID;

/**
 * What the room reacted to one comment with, as one person sees it: how many people chose each reaction, and which one is theirs. Who else
 * reacted is not told: the counts are enough to draw, and the events that follow carry the changes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
public record CommentReactions(UUID commentId, Map<Reaction, Integer> counts, @Nullable Reaction mine) {
}
