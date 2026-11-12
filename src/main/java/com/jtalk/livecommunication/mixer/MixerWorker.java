package com.jtalk.livecommunication.mixer;

import com.jtalk.livecommunication.domain.enums.MixerAction;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.model.MixerAssignment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/** Applies START / STOP assignments: one mix loop plus transport session per (room, mode). */
public final class MixerWorker implements AutoCloseable {

    private record Key(UUID roomId, MixerMode mode) {
    }

    private record Running(RoomMixLoop loop, RoomAudioTransport.AudioSession session) implements AutoCloseable {
        @Override
        public void close() {
            loop.close();
            session.close();
        }
    }

    private final List<RoomAudioTransport> transports;
    private final IntFunction<FrameEncoder> encoderForBitrate;
    private final Map<Key, Running> running = new HashMap<>();

    public MixerWorker(List<RoomAudioTransport> transports, IntFunction<FrameEncoder> encoderForBitrate) {
        this.transports = transports;
        this.encoderForBitrate = encoderForBitrate;
    }

    public static int bitrate(MixerMode mode) {
        return mode == MixerMode.FALLBACK ? 8_000 : 32_000;
    }

    public synchronized void handle(MixerAssignment assignment) {
        Key key = new Key(assignment.roomId(), assignment.mode());
        if (assignment.action() == MixerAction.STOP) {
            Running stopped = running.remove(key);
            if (stopped != null) {
                stopped.close();
            }
            return;
        }
        if (running.containsKey(key)) {
            return;
        }
        RoomAudioTransport transport = transports.stream()
                .filter(t -> t.supports(assignment.mode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transport supports mode " + assignment.mode()));
        RoomMixLoop loop = new RoomMixLoop(encoderForBitrate.apply(bitrate(assignment.mode())));
        RoomAudioTransport.AudioSession session = transport.open(assignment.roomId(), assignment.mode(), loop);
        loop.start(session::publish);
        running.put(key, new Running(loop, session));
    }

    @Override
    public synchronized void close() {
        running.values().forEach(Running::close);
        running.clear();
    }
}
