package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.dto.ApiMapper;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.repository.LiveStatsRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.repository.VoiceRoomRepository;
import com.jtalk.livecommunication.service.RoomFeed;
import com.jtalk.livecommunication.service.VoiceRoomService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.annotation.CacheInvalidate;
import io.micronaut.cache.annotation.Cacheable;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.transaction.annotation.Transactional;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.jtalk.livecommunication.config.CommunicationConstants.MAX_PAGE_SIZE;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class VoiceRoomServiceImpl implements VoiceRoomService {

    private static final String FEED_CACHE = "active-rooms-feed";
    @SuppressWarnings("unchecked")
    private static final Class<CursoredPage<VoiceRoomResponse>> FEED_PAGE = (Class<CursoredPage<VoiceRoomResponse>>) (Class<?>) CursoredPage.class;
    private static final Sort NEWEST_FIRST = Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final VoiceRoomRepository rooms;
    private final StageRepository stage;
    private final LiveStatsRepository liveStats;
    private final LiveKitGateway gateway;
    private final ApplicationEventPublisher<VoiceRoomClosed> closedEvents;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;
    private final CacheManager<?> caches;
    private final RoomFeed feed;

    @Override
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public RoomSessionResponse createRoom(UUID hostId, CreateVoiceRoomRequest request) {
        return rooms.findFirstByHostIdAndStatus(hostId, VoiceRoomStatus.ACTIVE)
                .map(existing -> rejoin(existing, hostId))   // a host runs one room at a time
                .orElseGet(() -> open(hostId, request));
    }

    private RoomSessionResponse rejoin(VoiceRoom room, UUID hostId) {
        log.info("Host {} already has active room {}, re-joining it", hostId, room.id());
        return new RoomSessionResponse(toResponse(room), gateway.serverUrl(), gateway.generateToken(room.id(), hostId, true),
                ParticipantRole.HOST, true);
    }

    private RoomSessionResponse open(UUID hostId, CreateVoiceRoomRequest request) {
        VoiceRoom saved = rooms.save(VoiceRoom.open(request.title(), request.topic(), hostId, request.targetLanguage(),
                request.nativeLanguage(), request.seatsOrDefault()));
        try {
            gateway.createRoom(saved.id());
            stage.initializeStage(saved.id(), hostId);
        } catch (RuntimeException e) {
            rooms.deleteById(saved.id());   // never leave an open room whose media room or stage does not exist
            throw e;
        }
        String token = gateway.generateToken(saved.id(), hostId, true);
        log.info("Created voice room {} hosted by {}", saved.id(), hostId);

        VoiceRoomResponse opened = toResponse(saved);
        feed.opened(opened);
        return new RoomSessionResponse(opened, gateway.serverUrl(), token, ParticipantRole.HOST, true);
    }

    @Override
    public CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable String targetLanguage, int size, @Nullable Instant afterCreatedAt,
                                                           @Nullable UUID afterId) {
        return caches.getCache(FEED_CACHE).get(Arrays.asList(targetLanguage, size, afterCreatedAt, afterId), FEED_PAGE,
                () -> loadFeed(targetLanguage, size, afterCreatedAt, afterId));
    }

    private CursoredPage<VoiceRoomResponse> loadFeed(@Nullable String targetLanguage, int size, @Nullable Instant afterCreatedAt,
                                                     @Nullable UUID afterId) {
        CursoredPageable pageable = afterCreatedAt == null || afterId == null
                ? CursoredPageable.from(size, NEWEST_FIRST)
                : CursoredPageable.from(0, Pageable.Cursor.of(afterCreatedAt, afterId), Pageable.Mode.CURSOR_NEXT, size, NEWEST_FIRST, false);
        CursoredPage<VoiceRoom> page = targetLanguage == null || targetLanguage.isBlank()
                ? rooms.findByStatus(VoiceRoomStatus.ACTIVE, pageable)
                : rooms.findByStatusAndTargetLanguage(VoiceRoomStatus.ACTIVE, targetLanguage, pageable);
        Map<UUID, RoomLiveStats> live = liveStats.find(page.getContent().stream().map(VoiceRoom::id).toList());
        return page.map(room -> mapper.toResponse(room, live.get(room.id())));
    }

    @Override
    public List<VoiceRoomResponse> openRooms() {
        return loadFeed(null, MAX_PAGE_SIZE, null, null).getContent();
    }

    @Override
    public VoiceRoomResponse getRoom(UUID roomId) {
        return toResponse(getRequiredActiveRoom(roomId));
    }

    @Override
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public VoiceRoomResponse updateRoom(UUID roomId, UUID requesterId, UpdateVoiceRoomRequest request) {
        VoiceRoom room = loadForUpdate(roomId);
        access.requireHost(room, requesterId, "update room settings");
        VoiceRoomResponse updated = toResponse(rooms.update(room.withSettings(request.title(), request.topic(), request.maxStageSeats())));
        feed.updated(updated);
        return updated;
    }

    /**
     * The transaction covers only the database write. Everything else (clearing Redis state, deleting the LiveKit room)
     * happens in {@code @TransactionalEventListener}s after the commit, so no network call ever holds a database
     * connection, and nothing runs if the write rolls back.
     */
    @Override
    @Transactional
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public void closeRoom(UUID roomId, UUID requesterId) {
        VoiceRoom room = loadForUpdate(roomId);
        access.requireHost(room, requesterId, "close this room");
        rooms.update(room.close());
        closedEvents.publishEvent(new VoiceRoomClosed(roomId));
        log.info("Closed voice room {} by host {}", roomId, requesterId);
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        feed.closed(event.roomId());
        try {
            gateway.deleteRoom(event.roomId());
        } catch (RuntimeException e) {
            // The room is already closed in our source of truth; LiveKit empties idle rooms by itself.
            log.warn("Room {} closed but media server cleanup failed", event.roomId(), e);
        }
    }

    /**
     * The one cached lookup behind both {@link #findActiveRoom} and {@link #getRequiredActiveRoom}: a room is read from PostgreSQL at most
     * once per cache lifetime, however many requests and LiveKit webhooks ask. "No such room" is cached too, because when a room closes
     * LiveKit sends a webhook for every participant, and each would otherwise be a query for a row that is not there. Closing or editing a
     * room evicts it ({@code @CacheInvalidate}), and a room that exists is always found: ids are never reused.
     */
    @Cacheable(value = "room-metadata", atomic = true)
    RoomLookup lookupActiveRoom(UUID roomId) {
        return new RoomLookup(rooms.findByIdAndStatus(roomId, VoiceRoomStatus.ACTIVE).orElse(null));
    }

    /**
     * What the cache holds: the room, or nothing. A holder rather than an {@code Optional} because the cache never stores a null, and
     * because it hands a cached {@code Optional} back wrapped in another one.
     */
    record RoomLookup(@Nullable VoiceRoom room) {

        Optional<VoiceRoom> asOptional() {
            return Optional.ofNullable(room);
        }
    }

    @Override
    public Optional<VoiceRoom> findActiveRoom(UUID roomId) {
        return lookupActiveRoom(roomId).asOptional();
    }

    @Override
    public VoiceRoom getRequiredActiveRoom(UUID roomId) {
        return required(lookupActiveRoom(roomId).asOptional(), roomId);
    }

    /** For a write: straight from PostgreSQL, never the cache, because the whole row is written back and a stale copy would undo a change. */
    private VoiceRoom loadForUpdate(UUID roomId) {
        return required(rooms.findByIdAndStatus(roomId, VoiceRoomStatus.ACTIVE), roomId);
    }

    private static VoiceRoom required(Optional<VoiceRoom> room, UUID roomId) {
        return room.orElseThrow(() -> ProblemType.VOICE_ROOM_NOT_FOUND.because("Voice room " + roomId + " does not exist or is closed"));
    }

    @Override
    public VoiceRoomResponse toResponse(VoiceRoom room) {
        return mapper.toResponse(room, liveStats.find(List.of(room.id())).get(room.id()));
    }
}
