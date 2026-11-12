package com.jtalk.livecommunication.mixer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OffHeapPcmRingBufferTest {

    @Test
    void readsBackWhatWasWrittenAcrossTheWrapAround() {
        try (var buffer = new OffHeapPcmRingBuffer(8)) {
            short[] out = new short[6];
            assertEquals(6, buffer.write(new short[]{1, 2, 3, 4, 5, 6}, 0, 6));
            assertEquals(4, buffer.read(out, 0, 4));          // consume 1..4, write position now wraps
            assertEquals(5, buffer.write(new short[]{7, 8, 9, 10, 11}, 0, 5));
            assertEquals(7, buffer.available());

            short[] all = new short[7];
            assertEquals(7, buffer.read(all, 0, 7));
            assertArrayEquals(new short[]{5, 6, 7, 8, 9, 10, 11}, all);
            assertEquals(0, buffer.available());
        }
    }

    @Test
    void dropsWhatDoesNotFitInsteadOfBlockingOrOverwriting() {
        try (var buffer = new OffHeapPcmRingBuffer(4)) {
            assertEquals(4, buffer.write(new short[]{1, 2, 3, 4, 5, 6}, 0, 6));
            short[] out = new short[4];
            buffer.read(out, 0, 4);
            assertArrayEquals(new short[]{1, 2, 3, 4}, out);
        }
    }

    @Test
    void readReturnsOnlyWhatIsBuffered() {
        try (var buffer = new OffHeapPcmRingBuffer(16)) {
            buffer.write(new short[]{9, 9}, 0, 2);
            assertEquals(2, buffer.read(new short[10], 0, 10));
            assertEquals(0, buffer.read(new short[10], 0, 10));
        }
    }

    @Test
    void producerAndConsumerThreadsSeeEverySampleInOrder() throws Exception {
        int total = 200_000;
        try (var buffer = new OffHeapPcmRingBuffer(960)) {
            Thread producer = Thread.ofPlatform().start(() -> {
                short[] one = new short[1];
                for (int i = 0; i < total; ) {
                    one[0] = (short) i;
                    if (buffer.write(one, 0, 1) == 1) {
                        i++;
                    }
                }
            });
            short[] one = new short[1];
            for (int expected = 0; expected < total; ) {
                if (buffer.read(one, 0, 1) == 1) {
                    assertEquals((short) expected, one[0], "sample " + expected);
                    expected++;
                }
            }
            producer.join();
        }
    }
}
