package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface VoiceRoomRepository extends CrudRepository<VoiceRoom, UUID> {

    /**
     * Keyset ("seek") pagination: Micronaut Data turns the cursor into {@code WHERE (created_at, id) < (?, ?)}, so
     * deep pages cost the same as the first and are stable while rooms are created and closed. The sort (newest
     * first, id as tie-breaker) is carried by the pageable.
     */
    CursoredPage<VoiceRoom> findByStatus(VoiceRoomStatus status, CursoredPageable pageable);
}
