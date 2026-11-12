package com.jtalk.livecommunication.domain.mapper;

import com.jtalk.livecommunication.domain.dto.request.QualityReportRequest;
import com.jtalk.livecommunication.domain.dto.response.JoinRoomResponse;
import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.model.QualityReport;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.model.StageState;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Domain <-> API mappings, generated at compile time by MapStruct. A DTO field with no source fails the build
 * ({@code unmappedTargetPolicy = ERROR}) instead of silently going out empty.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.JAKARTA, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ApiMapper {

    /** Room metadata (PostgreSQL) merged with its real-time numbers (Redis). */
    VoiceRoomResponse toResponse(VoiceRoom room, RoomLiveStats live);

    ParticipantResponse toResponse(Participant participant);

    List<ParticipantResponse> toParticipantResponses(List<Participant> participants);

    StageResponse toResponse(StageState state);

    @Mapping(target = "mediaUrl", source = "connection.url")
    @Mapping(target = "mediaToken", source = "connection.token")
    JoinRoomResponse toResponse(ParticipantService.Joined joined);

    QualityReport toReport(QualityReportRequest request);
}
