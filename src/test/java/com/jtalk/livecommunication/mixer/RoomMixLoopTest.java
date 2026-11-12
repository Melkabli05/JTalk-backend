package com.jtalk.livecommunication.mixer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomMixLoopTest {

    /** Records the PCM it is asked to encode instead of compressing it. */
    private static final class RecordingEncoder implements FrameEncoder {
        final List<short[]> frames = new ArrayList<>();
        boolean closed;

        @Override
        public int encode(short[] pcm, byte[] packet) {
            frames.add(pcm.clone());
            return 7;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static short[] constantFrame(int value) {
        short[] frame = new short[RoomMixLoop.FRAME_SAMPLES];
        Arrays.fill(frame, (short) value);
        return frame;
    }

    @Test
    void sumsAllSpeakersIntoOneEncodedFrame() {
        var encoder = new RecordingEncoder();
        List<Integer> packetLengths = new ArrayList<>();
        try (var loop = new RoomMixLoop(encoder)) {
            loop.bufferFor("alice").write(constantFrame(1000), 0, RoomMixLoop.FRAME_SAMPLES);
            loop.bufferFor("bob").write(constantFrame(2000), 0, RoomMixLoop.FRAME_SAMPLES);

            assertTrue(loop.tick((packet, length) -> packetLengths.add(length)));
        }
        assertEquals(List.of(7), packetLengths);
        assertEquals(1, encoder.frames.size());
        for (short sample : encoder.frames.getFirst()) {
            assertEquals(3000, sample);
        }
        assertTrue(encoder.closed);
    }

    @Test
    void loudSumsAreSoftClippedNotWrapped() {
        var encoder = new RecordingEncoder();
        try (var loop = new RoomMixLoop(encoder)) {
            loop.bufferFor("a").write(constantFrame(30000), 0, RoomMixLoop.FRAME_SAMPLES);
            loop.bufferFor("b").write(constantFrame(30000), 0, RoomMixLoop.FRAME_SAMPLES);
            loop.tick((packet, length) -> { });
        }
        short mixed = encoder.frames.getFirst()[0];
        assertTrue(mixed > 30000 && mixed <= Short.MAX_VALUE, "mixed sample " + mixed);
    }

    @Test
    void silenceSendsNothing() {
        var encoder = new RecordingEncoder();
        try (var loop = new RoomMixLoop(encoder)) {
            loop.bufferFor("alice");   // present but silent
            assertFalse(loop.tick((packet, length) -> {
                throw new AssertionError("nothing should be sent");
            }));
        }
        assertTrue(encoder.frames.isEmpty());
    }

    @Test
    void aSpeakerThatRanDryJustContributesWhatItHas() {
        var encoder = new RecordingEncoder();
        try (var loop = new RoomMixLoop(encoder)) {
            loop.bufferFor("alice").write(constantFrame(500), 0, RoomMixLoop.FRAME_SAMPLES);
            loop.bufferFor("bob").write(constantFrame(100), 0, 100);   // underrun after 100 samples
            loop.tick((packet, length) -> { });
        }
        short[] mixed = encoder.frames.getFirst();
        assertEquals(600, mixed[99]);
        assertEquals(500, mixed[100]);
    }

    @Test
    void removedSpeakersStopContributing() {
        var encoder = new RecordingEncoder();
        try (var loop = new RoomMixLoop(encoder)) {
            loop.bufferFor("alice").write(constantFrame(500), 0, RoomMixLoop.FRAME_SAMPLES);
            loop.removeSpeaker("alice");
            assertFalse(loop.tick((packet, length) -> { }));
        }
    }
}
