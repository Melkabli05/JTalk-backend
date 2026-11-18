package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import org.reactivestreams.Publisher;

import java.util.UUID;

/**
 * What happens to the list of open rooms, for every hallway that is watching. It travels over Redis pub/sub, so a hallway hears about
 * rooms handled by any instance. Best effort: a message sent while Redis is unreachable is lost, and the room is opened or closed all the same.
 */
public interface RoomFeed {

    /** What happens from the moment of subscribing, on any instance. A subscriber that falls too far behind is cut off and must reconnect. */
    Publisher<RoomFeedEvent> changes();

    void opened(VoiceRoomResponse room);

    void updated(VoiceRoomResponse room);

    void closed(UUID roomId);

    void stats(UUID roomId, RoomLiveStats live);
}
