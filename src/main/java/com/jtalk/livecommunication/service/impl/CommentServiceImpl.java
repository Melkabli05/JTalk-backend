package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.CommentType;
import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.domain.model.ChatEvent;
import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.RoomComment;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import com.jtalk.livecommunication.repository.CommentRepository;
import com.jtalk.livecommunication.service.CommentService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.zalando.problem.ThrowableProblem;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

@Singleton
@RequiredArgsConstructor
class CommentServiceImpl implements CommentService {

    /** Control characters (a line break is the one that stays) and the text-direction overrides that can make one person's words read as another's. */
    private static final Pattern UNWANTED = Pattern.compile("[\\p{Cc}&&[^\\n]]|[\\u202A-\\u202E]");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    /** Something a person can see: a letter, a number, a mark, a symbol or punctuation. Invisible characters alone make no comment. */
    private static final Pattern VISIBLE = Pattern.compile("[\\p{L}\\p{N}\\p{M}\\p{P}\\p{S}]");

    private final VoiceRoomService rooms;
    private final CommentRepository comments;
    private final RoomAccessValidator access;
    private final LiveKitGateway gateway;
    private final ReactionThrottle throttle;
    private final Clock clock;

    @Override
    public ChatState chat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        return comments.state(roomId, userId, room.isHost(userId)).orElseThrow(() -> notInRoom(roomId, userId));
    }

    @Override
    public RoomComment post(UUID roomId, UUID userId, CommentType type, String content, @Nullable String correctedContent, @Nullable UUID replyTo) {
        rooms.getRequiredActiveRoom(roomId);
        String said = clean(content);
        String corrected = correctedContent == null ? null : clean(correctedContent);
        if (corrected != null && !isVisible(corrected)) {
            corrected = null;
        }
        if (!isVisible(said)) {
            throw ProblemType.INVALID_COMMENT.because("A comment cannot be empty");
        }
        if (type == CommentType.CORRECTION && corrected == null) {
            throw ProblemType.INVALID_COMMENT.because("A correction needs the corrected phrase");
        }
        if (type == CommentType.TEXT && corrected != null) {
            throw ProblemType.INVALID_COMMENT.because("Only a correction has a corrected phrase");
        }

        RoomComment comment = new RoomComment(UUID.randomUUID(), userId, type, said, corrected, replyTo, clock.instant());
        switch (comments.post(roomId, userId, comment)) {
            case NOT_IN_ROOM -> throw notInRoom(roomId, userId);
            case MUTED -> throw ProblemType.CHAT_MUTED.because("The host has muted you in this room's chat");
            case TOO_FAST -> throw ProblemType.COMMENTING_TOO_FAST.because("Wait a moment before commenting again");
            case KEPT -> gateway.broadcastChatEvent(ChatEvent.posted(roomId, comment.createdAt(), comment));
        }
        return comment;
    }

    @Override
    public void react(UUID roomId, UUID userId, UUID commentId, Reaction reaction) {
        change(roomId, userId, commentId, reaction);
    }

    @Override
    public void unreact(UUID roomId, UUID userId, UUID commentId) {
        change(roomId, userId, commentId, null);
    }

    private void change(UUID roomId, UUID userId, UUID commentId, @Nullable Reaction reaction) {
        rooms.getRequiredActiveRoom(roomId);
        if (!throttle.allow(roomId, userId)) {
            throw ProblemType.REACTING_TOO_FAST.because("Wait a moment before reacting again");
        }
        CommentRepository.Reacted reacted = comments.react(roomId, userId, commentId, reaction);
        switch (reacted.outcome()) {
            case NOT_IN_ROOM -> throw notInRoom(roomId, userId);
            case MUTED -> throw ProblemType.CHAT_MUTED.because("The host has muted you in this room's chat");
            case NO_SUCH_COMMENT -> throw ProblemType.COMMENT_NOT_FOUND.because("Comment " + commentId + " is not in this room's chat");
            case UNCHANGED -> { }
            case CHANGED -> gateway.broadcastChatEvent(ChatEvent.reacted(roomId, clock.instant(), commentId, userId, reaction, reacted.previous()));
        }
    }

    @Override
    public void delete(UUID roomId, UUID hostId, UUID commentId) {
        requireHost(roomId, hostId, "delete comments");
        if (comments.delete(roomId, commentId)) {
            gateway.broadcastChatEvent(ChatEvent.deleted(roomId, clock.instant(), commentId));
        }
    }

    @Override
    public void mute(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = requireHost(roomId, hostId, "mute people in the chat");
        if (room.isHost(targetUserId)) {
            throw ProblemType.CANNOT_MUTE_HOST.because("The host cannot be muted in their own chat");
        }
        comments.mute(roomId, targetUserId);
        gateway.broadcastChatEvent(ChatEvent.muted(roomId, clock.instant(), targetUserId));
    }

    @Override
    public void unmute(UUID roomId, UUID hostId, UUID targetUserId) {
        requireHost(roomId, hostId, "unmute people in the chat");
        comments.unmute(roomId, targetUserId);
        gateway.broadcastChatEvent(ChatEvent.unmuted(roomId, clock.instant(), targetUserId));
    }

    @Override
    public void pinTopic(UUID roomId, UUID hostId, String topic) {
        requireHost(roomId, hostId, "pin a topic");
        String pinned = clean(topic);
        if (!isVisible(pinned)) {
            throw ProblemType.INVALID_COMMENT.because("A topic cannot be empty");
        }
        comments.pinTopic(roomId, pinned);
        gateway.broadcastChatEvent(ChatEvent.topic(roomId, clock.instant(), pinned));
    }

    @Override
    public void clearTopic(UUID roomId, UUID hostId) {
        requireHost(roomId, hostId, "clear the topic");
        comments.clearTopic(roomId);
        gateway.broadcastChatEvent(ChatEvent.topic(roomId, clock.instant(), null));
    }

    private static ThrowableProblem notInRoom(UUID roomId, UUID userId) {
        return ProblemType.NOT_IN_ROOM.because(userId + " has not joined room " + roomId);
    }

    private static boolean isVisible(String text) {
        return VISIBLE.matcher(text).find();
    }

    private VoiceRoom requireHost(UUID roomId, UUID hostId, String action) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, action);
        return room;
    }

    /** Text as it is kept: without control characters or direction overrides, runs of blank lines cut to one, trimmed. Any script and any emoji stay. */
    private static String clean(String text) {
        return BLANK_LINES.matcher(UNWANTED.matcher(text).replaceAll("")).replaceAll("\n\n").strip();
    }
}
