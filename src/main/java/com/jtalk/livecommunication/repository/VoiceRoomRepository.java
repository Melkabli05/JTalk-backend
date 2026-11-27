package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface VoiceRoomRepository extends CrudRepository<VoiceRoom, UUID> {

    Optional<VoiceRoom> findFirstByHostIdAndStatus(UUID hostId, VoiceRoomStatus status);
    /** Every room of the host in this state: a host runs one at a time, but a deletion must not rely on it. */
    List<VoiceRoom> findByHostIdAndStatus(UUID hostId, VoiceRoomStatus status);

    /**
     * Closes the room only if it is still open, in one statement, so two callers closing it at the same moment (a host pressing End twice,
     * two instances hearing the same account deletion) cannot both think they did.
     *
     * @return 1 if this call closed it, 0 if it was already closed or does not exist
     */
    @Query("UPDATE voice_rooms SET status = 'CLOSED', updated_at = NOW() WHERE id = :id AND status = 'ACTIVE'")
    int closeIfActive(UUID id);

    /** The ids of every room in this state: what a person must be taken out of when their account is deleted. */
    List<UUID> findIdByStatus(VoiceRoomStatus status);

    List<VoiceRoom> findByStatus(VoiceRoomStatus status);

    /** Erases the rooms a host ever ran: their titles and topics are the person's own words. */
    void deleteByHostId(UUID hostId);

    Optional<VoiceRoom> findByIdAndStatus(UUID id, VoiceRoomStatus status);

    /**
     * Keyset ("seek") pagination: Micronaut Data turns the cursor into {@code WHERE (created_at, id) < (?, ?)}, so
     * deep pages cost the same as the first and are stable while rooms are created and closed. The sort (newest
     * first, id as tie-breaker) is carried by the pageable.
     */
    CursoredPage<VoiceRoom> findByStatus(VoiceRoomStatus status, CursoredPageable pageable);

    CursoredPage<VoiceRoom> findByStatusAndTargetLanguage(VoiceRoomStatus status, String targetLanguage, CursoredPageable pageable);
}
