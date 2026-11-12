package com.jtalk.livecommunication.mixer;

import com.jtalk.livecommunication.domain.enums.MixerAction;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.model.MixerAssignment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixerWorkerTest {

    private static final class FakeTransport implements RoomAudioTransport {
        final MixerMode mode;
        final List<UUID> opened = new ArrayList<>();
        final List<Boolean> closed = new ArrayList<>();
        final List<Integer> published = new ArrayList<>();
        SpeakerAudioInput input;

        FakeTransport(MixerMode mode) {
            this.mode = mode;
        }

        @Override
        public boolean supports(MixerMode requested) {
            return requested == mode;
        }

        @Override
        public AudioSession open(UUID roomId, MixerMode requested, SpeakerAudioInput input) {
            opened.add(roomId);
            this.input = input;
            return new AudioSession() {
                @Override
                public void publish(byte[] packet, int length) {
                    published.add(length);
                }

                @Override
                public void close() {
                    closed.add(true);
                }
            };
        }
    }

    private static final FrameEncoder NOOP_ENCODER = new FrameEncoder() {
        @Override
        public int encode(short[] pcm, byte[] packet) {
            return 1;
        }

        @Override
        public void close() {
        }
    };

    private final UUID room = UUID.randomUUID();

    @Test
    void startOpensOneSessionAndDuplicateStartsAreIgnored() {
        var transport = new FakeTransport(MixerMode.FALLBACK);
        try (var worker = new MixerWorker(List.of(transport), bitrate -> NOOP_ENCODER)) {
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.START));
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.START));
            assertEquals(List.of(room), transport.opened);
        }
    }

    @Test
    void stopClosesTheSessionAndIsIdempotent() {
        var transport = new FakeTransport(MixerMode.FALLBACK);
        try (var worker = new MixerWorker(List.of(transport), bitrate -> NOOP_ENCODER)) {
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.START));
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.STOP));
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.STOP));
            assertEquals(1, transport.closed.size());
        }
    }

    @Test
    void usesTheTransportThatSupportsTheModeAndTheRightBitrate() {
        var fallback = new FakeTransport(MixerMode.FALLBACK);
        var broadcast = new FakeTransport(MixerMode.BROADCAST);
        List<Integer> bitrates = new ArrayList<>();
        try (var worker = new MixerWorker(List.of(fallback, broadcast), bitrate -> {
            bitrates.add(bitrate);
            return NOOP_ENCODER;
        })) {
            worker.handle(new MixerAssignment(room, MixerMode.BROADCAST, MixerAction.START));
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.START));
        }
        assertEquals(List.of(room), broadcast.opened);
        assertEquals(List.of(room), fallback.opened);
        assertEquals(List.of(32_000, 8_000), bitrates);
    }

    @Test
    void failsLoudlyWhenNoTransportSupportsTheMode() {
        var transport = new FakeTransport(MixerMode.FALLBACK);
        try (var worker = new MixerWorker(List.of(transport), bitrate -> NOOP_ENCODER)) {
            var ex = assertThrows(IllegalStateException.class,
                    () -> worker.handle(new MixerAssignment(room, MixerMode.BROADCAST, MixerAction.START)));
            assertTrue(ex.getMessage().contains("BROADCAST"));
        }
    }

    @Test
    void audioWrittenByTheTransportIsMixedAndPublishedBackToIt() throws InterruptedException {
        var transport = new FakeTransport(MixerMode.FALLBACK);
        try (var worker = new MixerWorker(List.of(transport), bitrate -> NOOP_ENCODER)) {
            worker.handle(new MixerAssignment(room, MixerMode.FALLBACK, MixerAction.START));
            short[] frame = new short[RoomMixLoop.FRAME_SAMPLES];
            transport.input.bufferFor("alice").write(frame, 0, frame.length);

            long deadline = System.currentTimeMillis() + 2_000;
            while (transport.published.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(List.of(1), transport.published);
        }
    }
}
