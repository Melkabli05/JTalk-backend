package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.config.TextSanitizer;
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

import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Singleton
@RequiredArgsConstructor
class CommentServiceImpl implements CommentService {

    private static final Pattern BLOCKED_IMAGE_PATTERN = Pattern.compile(
            "(?is)(data:image/|<img\\b|!\\[[^\\]]*\\]\\([^)]+\\)|https?://\\S+\\.(?:png|jpe?g|webp|gif|svg|bmp|avif)(?:[?#]\\S*)?)");
    private static final Set<String> ALLOWED_GIF_HOSTS = Set.of(
            "media.tenor.com", "c.tenor.com", "media1.tenor.com",
            "media.giphy.com", "media0.giphy.com", "media1.giphy.com",
            "media2.giphy.com", "media3.giphy.com", "media4.giphy.com", "i.giphy.com");

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
    public RoomComment post(UUID roomId, UUID userId, CommentType type, String content, @Nullable String correctedContent,
                            @Nullable UUID replyTo, @Nullable String tip) {
        rooms.getRequiredActiveRoom(roomId);
        String said = clean(content);
        String corrected = correctedContent == null ? null : clean(correctedContent);
        if (corrected != null && !isVisible(corrected)) {
            corrected = null;
        }
        String cleanedTip = tip == null ? null : TextSanitizer.cleanSingleLine(tip);
        if (cleanedTip != null && !isVisible(cleanedTip)) {
            cleanedTip = null;
        }
        if (!isVisible(said)) {
            throw ProblemType.INVALID_COMMENT.because("A comment cannot be empty");
        }
        if (type == CommentType.CORRECTION && corrected == null) {
            throw ProblemType.INVALID_COMMENT.because("A correction needs the corrected phrase");
        }
        if (type != CommentType.CORRECTION && (corrected != null || cleanedTip != null)) {
            throw ProblemType.INVALID_COMMENT.because("Only a correction has a corrected phrase");
        }
        if (type == CommentType.GIF) {
            if (!isValidGifUrl(said)) {
                throw ProblemType.INVALID_COMMENT.because("Only approved GIF links are allowed");
            }
        } else if (containsImagePayload(said) || containsImagePayload(corrected) || containsImagePayload(cleanedTip)) {
            throw ProblemType.INVALID_COMMENT.because("Images are not supported in comments");
        }

        RoomComment comment = new RoomComment(UUID.randomUUID(), userId, type, said, corrected, replyTo, clock.instant(), null, cleanedTip);
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
        VoiceRoom room = requireModeratorOrHost(roomId, hostId, "delete comments");
        if (!room.isHost(hostId)) {
            comments.authorOf(roomId, commentId)
                    .filter(room::isHost)
                    .ifPresent(author -> {
                        throw ProblemType.NOT_ROOM_HOST.because("Moderators cannot delete the host's comments");
                    });
        }
        if (comments.delete(roomId, commentId)) {
            gateway.broadcastChatEvent(ChatEvent.deleted(roomId, clock.instant(), commentId));
        }
    }

    @Override
    public void mute(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (room.isHost(targetUserId)) {
            access.requireHost(room, hostId, "mute people in the chat");
            throw ProblemType.CANNOT_MUTE_HOST.because("The host cannot be muted in their own chat");
        }
        access.requireCanModerateTarget(room, hostId, targetUserId, "mute people in the chat");
        comments.mute(roomId, targetUserId);
        gateway.broadcastChatEvent(ChatEvent.muted(roomId, clock.instant(), targetUserId));
    }

    @Override
    public void unmute(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireCanModerateTarget(room, hostId, targetUserId, "unmute people in the chat");
        comments.unmute(roomId, targetUserId);
        gateway.broadcastChatEvent(ChatEvent.unmuted(roomId, clock.instant(), targetUserId));
    }

    @Override
    public void pinTopic(UUID roomId, UUID hostId, String topic) {
        requireModeratorOrHost(roomId, hostId, "pin a topic");
        String pinned = clean(topic);
        if (!isVisible(pinned)) {
            throw ProblemType.INVALID_COMMENT.because("A topic cannot be empty");
        }
        comments.pinTopic(roomId, pinned);
        gateway.broadcastChatEvent(ChatEvent.topic(roomId, clock.instant(), pinned));
    }

    @Override
    public void clearTopic(UUID roomId, UUID hostId) {
        requireModeratorOrHost(roomId, hostId, "clear the topic");
        comments.clearTopic(roomId);
        gateway.broadcastChatEvent(ChatEvent.topic(roomId, clock.instant(), null));
    }

    @Override
    public void pinComment(UUID roomId, UUID hostId, UUID commentId) {
        requireModeratorOrHost(roomId, hostId, "pin comments");
        if (comments.pinComment(roomId, commentId)) {
            gateway.broadcastChatEvent(ChatEvent.pinned(roomId, clock.instant(), commentId));
        } else {
            throw ProblemType.COMMENT_NOT_FOUND.because("Comment " + commentId + " is not in this room's chat");
        }
    }

    @Override
    public void unpinComment(UUID roomId, UUID hostId, UUID commentId) {
        requireModeratorOrHost(roomId, hostId, "unpin comments");
        if (comments.unpinComment(roomId, commentId)) {
            gateway.broadcastChatEvent(ChatEvent.unpinned(roomId, clock.instant(), commentId));
        } else {
            throw ProblemType.COMMENT_NOT_FOUND.because("Comment " + commentId + " is not in this room's chat");
        }
    }

    private static ThrowableProblem notInRoom(UUID roomId, UUID userId) {
        return ProblemType.NOT_IN_ROOM.because(userId + " has not joined room " + roomId);
    }

    private static boolean isVisible(String text) {
        return TextSanitizer.isVisible(text);
    }

    private VoiceRoom requireModeratorOrHost(UUID roomId, UUID requesterId, String action) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireModeratorOrHost(room, requesterId, action);
        return room;
    }

    private static String clean(String text) {
        return TextSanitizer.cleanMultiLine(text);
    }

    private static boolean containsImagePayload(@Nullable String text) {
        return text != null && BLOCKED_IMAGE_PATTERN.matcher(text).find();
    }

    private static boolean isValidGifUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return ALLOWED_GIF_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
