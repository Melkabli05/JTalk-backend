package com.jtalk.livecommunication.mixer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the real libopus through FFM; skipped where libopus is not installed (brew/apt install opus). */
class NativeOpusEncoderTest {

    @Test
    void encodesASineFrameIntoAnOpusPacketOfPlausibleSize() {
        assumeTrue(NativeOpusEncoder.isAvailable(), "libopus not installed");
        short[] pcm = new short[RoomMixLoop.FRAME_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (12_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
        }
        byte[] packet = new byte[1275];

        try (var encoder = new NativeOpusEncoder(8_000)) {
            int length = encoder.encode(pcm, packet);
            // 8 kbps for 20 ms is about 20 bytes; allow generous slack for the codec's own decisions.
            assertTrue(length > 0 && length < 120, "packet length " + length);
        }
        try (var encoder = new NativeOpusEncoder(32_000)) {
            int length = encoder.encode(pcm, packet);
            assertTrue(length > 40 && length < 300, "packet length " + length);
        }
    }
}
