package com.jtalk.livecommunication.mixer;

/**
 * Keeps a sum of several speakers inside 16-bit range without the harsh distortion of hard clipping:
 * untouched up to the knee, then smoothly compressed towards full scale.
 */
public final class SoftKneeClipper {

    private static final float FULL_SCALE = 32768f;
    private static final float KNEE = 0.7f;

    private SoftKneeClipper() {
    }

    public static short clip(int sample) {
        float normalized = sample / FULL_SCALE;
        float magnitude = Math.abs(normalized);
        if (magnitude <= KNEE) {
            return (short) sample;
        }
        float overshoot = (magnitude - KNEE) / (1 - KNEE);
        float compressed = KNEE + (1 - KNEE) * (float) Math.tanh(overshoot);
        // Round the magnitude, then apply the sign: Math.round rounds halves upwards, which would make
        // negative samples differ from their positive mirror and add a DC bias to loud mixes.
        short magnitudeOut = (short) Math.round(compressed * (FULL_SCALE - 1));
        return normalized < 0 ? (short) -magnitudeOut : magnitudeOut;
    }
}
