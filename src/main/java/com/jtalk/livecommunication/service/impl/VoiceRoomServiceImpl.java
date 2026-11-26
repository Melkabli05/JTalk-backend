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
import com.jtalk.livecommunication.domain.model.RoomSettings;
import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import com.jtalk.livecommunication.repository.LiveStatsRepository;
import com.jtalk.livecommunication.repository.RosterRepository;
import com.jtalk.livecommunication.repository.SpeakerProfileRepository;
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
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.zalando.problem.ThrowableProblem;
import lombok.extern.slf4j.Slf4j;

import static com.jtalk.livecommunication.config.CommunicationConstants.MAX_PAGE_SIZE;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final RosterRepository roster;
    private final SpeakerProfileRepository speakerProfiles;
    private final LiveKitGateway gateway;
    private final ApplicationEventPublisher<VoiceRoomClosed> closedEvents;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;
    private final CacheManager<?> caches;
    private final RoomFeed feed;
    private final RoomEvents events;

    @Override
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public RoomSessionResponse createRoom(UUID hostId, CreateVoiceRoomRequest request) {
        return rooms.findFirstByHostIdAndStatus(hostId, VoiceRoomStatus.ACTIVE)
                .map(existing -> rejoin(existing, hostId))   // a host runs one room at a time
                .orElseGet(() -> open(hostId, request));
    }

    private RoomSessionResponse rejoin(VoiceRoom room, UUID hostId) {
        log.info("Host {} already has active room {}, re-joining it", hostId, room.id());
        return sessionFor(room, hostId, true);
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

        // A new room has nobody in it but the host on the stage, so there is nothing to read from Redis.
        VoiceRoomResponse opened = mapper.toResponse(saved, new RoomLiveStats(List.of(hostId), 0, 0));
        feed.opened(opened);
        return new RoomSessionResponse(opened, gateway.serverUrl(), token, ParticipantRole.HOST, true, false, null);
    }

    @Override
    public CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable String targetLanguage, int size, @Nullable Instant afterCreatedAt,
                                                           @Nullable UUID afterId) {
        return listActiveRooms(targetLanguage, size, afterCreatedAt, afterId, false);
    }

    @Override
    public CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable String targetLanguage, int size, @Nullable Instant afterCreatedAt,
                                                           @Nullable UUID afterId, boolean expandSpeakers) {
        CursoredPage<VoiceRoomResponse> page = caches.getCache(FEED_CACHE).get(
                Arrays.asList(targetLanguage, size, afterCreatedAt, afterId), FEED_PAGE,
                () -> loadFeed(targetLanguage, size, afterCreatedAt, afterId));
        return expandSpeakers ? expandPage(page) : page;
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
        return openRooms(null);
    }

    @Override
    public List<VoiceRoomResponse> openRooms(@Nullable String targetLanguage) {
        return expandRooms(loadFeed(targetLanguage, MAX_PAGE_SIZE, null, null).getContent());
    }

    @Override
    public VoiceRoomResponse getRoom(UUID roomId) {
        return getRoom(roomId, false);
    }

    @Override
    public VoiceRoomResponse getRoom(UUID roomId, boolean expandSpeakers) {
        VoiceRoomResponse room = toResponse(getRequiredActiveRoom(roomId));
        return expandSpeakers ? expandRoom(room) : room;
    }

    @Override
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public VoiceRoomResponse updateRoom(UUID roomId, UUID requesterId, UpdateVoiceRoomRequest request) {
        VoiceRoom room = loadForUpdate(roomId);
        access.requireHost(room, requesterId, "update room settings");
        VoiceRoom saved = rooms.update(room.withSettings(request.title(), request.topic(), request.maxStageSeats()));
        VoiceRoomResponse updated = toResponse(saved);
        feed.updated(updated);
        events.announceSettings(roomId, new RoomSettings(saved.title(), saved.topic(), saved.maxStageSeats(), saved.hostId()));
        return updated;
    }

    private CursoredPage<VoiceRoomResponse> expandPage(CursoredPage<VoiceRoomResponse> page) {
        Map<UUID, RoomSpeakerSummary> summaries = loadSummaries(page.getContent());
        return page.map(room -> attachSummaries(room, summaries));
    }

    private List<VoiceRoomResponse> expandRooms(List<VoiceRoomResponse> list) {
        if (list.isEmpty()) {
            return list;
        }
        Map<UUID, RoomSpeakerSummary> summaries = loadSummaries(list);
        return list.stream().map(room -> attachSummaries(room, summaries)).toList();
    }

    private VoiceRoomResponse expandRoom(VoiceRoomResponse room) {
        return attachSummaries(room, loadSummaries(List.of(room)));
    }

    private Map<UUID, RoomSpeakerSummary> loadSummaries(List<VoiceRoomResponse> list) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (VoiceRoomResponse room : list) {
            ids.add(room.hostId());
            if (room.speakers() != null) {
                ids.addAll(room.speakers());
            }
        }
        return speakerProfiles.findSummaries(ids);
    }

    private static VoiceRoomResponse attachSummaries(VoiceRoomResponse room, Map<UUID, RoomSpeakerSummary> summaries) {
        RoomSpeakerSummary host = summaries.get(room.hostId());
        List<RoomSpeakerSummary> speakers = room.speakers() == null
                ? List.of()
                : room.speakers().stream().map(summaries::get).filter(Objects::nonNull).toList();
        return room.withProfiles(host, speakers);
    }

    /**
     * The transaction covers only the database write. Everything else (clearing Redis state, deleting the LiveKit room) is {@link RoomClosure},
     * after the commit and off the request: no network call holds a database connection, nothing runs if the write rolls back, and the
     * host's answer waits for none of it.
     */
    @Override
    @Transactional
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public void closeRoom(UUID roomId, UUID requesterId) {
        VoiceRoom room = loadForUpdate(roomId);
        access.requireHost(room, requesterId, "close this room");
        if (rooms.closeIfActive(roomId) == 0) {
            throw ProblemType.VOICE_ROOM_NOT_FOUND.because("Voice room " + roomId + " does not exist or is closed");   // closed a moment ago by someone else
        }
        closedEvents.publishEvent(new VoiceRoomClosed(roomId));
        log.info("Closed voice room {} by host {}", roomId, requesterId);
    }

    @Override
    public List<UUID> activeRoomIdsHostedBy(UUID hostId) {
        return rooms.findByHostIdAndStatus(hostId, VoiceRoomStatus.ACTIVE).stream().map(VoiceRoom::id).toList();
    }

    @Override
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public void handOverRoom(UUID roomId, UUID fromHostId, UUID toUserId) {
        VoiceRoom room = loadForUpdate(roomId);
        if (!room.isHost(fromHostId)) {
            return;   // already handed over, by another instance hearing the same announcement
        }
        VoiceRoom saved = rooms.update(room.withHost(toUserId));
        feed.updated(toResponse(saved));
        events.announceSettings(roomId, new RoomSettings(saved.title(), saved.topic(), saved.maxStageSeats(), saved.hostId()));
        log.info("Room {} handed from {} to {}", roomId, fromHostId, toUserId);
    }

    @Override
    @CacheInvalidate(value = "room-metadata", parameters = "roomId")
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public void transferHost(UUID roomId, UUID currentHostId, UUID toUserId) {
        if (currentHostId.equals(toUserId)) {
            throw ProblemType.CANNOT_TRANSFER_HOST_TO_SELF.because("Choose a different participant to transfer the host role to");
        }
        VoiceRoom room = loadForUpdate(roomId);
        access.requireHost(room, currentHostId, "transfer host role");
        // Verify the target is actually in the room (in the roster)
        boolean targetPresent = roster.read(roomId).present().contains(toUserId);
        if (!targetPresent) {
            throw ProblemType.NOT_A_PARTICIPANT.because("User " + toUserId + " is not in this room");
        }
        VoiceRoom saved = rooms.update(room.withHost(toUserId));
        VoiceRoomResponse updated = toResponse(saved);
        feed.updated(updated);
        events.announceSettings(roomId, new RoomSettings(saved.title(), saved.topic(), saved.maxStageSeats(), saved.hostId()));
        log.info("Host of room {} transferred from {} to {}", roomId, currentHostId, toUserId);
    }

    @Override
    public List<UUID> openRoomIds() {
        return rooms.findIdByStatus(VoiceRoomStatus.ACTIVE);
    }

    @Override
    @CacheInvalidate(value = FEED_CACHE, all = true)
    public void eraseRoomsHostedBy(UUID hostId) {
        rooms.deleteByHostId(hostId);
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
    public RoomSessionResponse sessionFor(VoiceRoom room, UUID userId, boolean onStage) {
        var live = liveStats.findForSession(room.id(), userId);
        return new RoomSessionResponse(mapper.toResponse(room, live.stats()), gateway.serverUrl(),
                gateway.generateToken(room.id(), userId, onStage), room.roleOf(userId, onStage), onStage, live.chatMuted(), null);
    }

    private VoiceRoomResponse toResponse(VoiceRoom room) {
        return mapper.toResponse(room, liveStats.find(List.of(room.id())).get(room.id()));
    }
}
