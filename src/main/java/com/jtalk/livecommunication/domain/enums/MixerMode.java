package com.jtalk.livecommunication.domain.enums;

public enum MixerMode {
    /** 8 kbps mono mix for clients on bad connections, published back into the LiveKit room. */
    FALLBACK,
    /** 32 kbps mix for the audience tier, pushed to a broadcast relay. */
    BROADCAST
}
