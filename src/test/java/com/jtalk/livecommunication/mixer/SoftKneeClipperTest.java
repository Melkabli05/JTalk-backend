package com.jtalk.livecommunication.mixer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftKneeClipperTest {

    @Test
    void leavesQuietSignalsUntouched() {
        for (int sample : new int[]{0, 1, -1, 1000, -20000, 22000, -22000}) {
            assertEquals((short) sample, SoftKneeClipper.clip(sample));
        }
    }

    @Test
    void neverLeavesSixteenBitRangeEvenForHugeSums() {
        for (int sample : new int[]{40000, -40000, 100000, -100000, Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2}) {
            short clipped = SoftKneeClipper.clip(sample);
            assertTrue(clipped <= Short.MAX_VALUE && clipped >= -Short.MAX_VALUE, sample + " -> " + clipped);
        }
    }

    @Test
    void isMonotonicAndSymmetricAroundTheKnee() {
        short previous = Short.MIN_VALUE;
        for (int sample = -70_000; sample <= 70_000; sample += 50) {
            short clipped = SoftKneeClipper.clip(sample);
            assertTrue(clipped >= previous, "not monotonic at " + sample);
            previous = clipped;
            assertEquals(-clipped, SoftKneeClipper.clip(-sample), "asymmetric at " + sample);
        }
    }
}
