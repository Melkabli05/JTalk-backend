package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface VoiceRoomRepository extends CrudRepository<VoiceRoom, UUID> {

    /**
     * Newest first, with the id as a tie-breaker so rooms created in the same instant keep a stable order across
     * pages. Paging uses Micronaut Data's built-in {@code Pageable} binding (there is no HTTP binder for cursored
     * pageables, so keyset pagination would need a hand-written cursor codec).
     */
    List<VoiceRoom> findByStatusOrderByCreatedAtDescAndIdDesc(VoiceRoomStatus status, Pageable pageable);
}
