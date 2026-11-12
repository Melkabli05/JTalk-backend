package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.MixerAction;
import com.jtalk.livecommunication.domain.enums.MixerMode;

import java.util.UUID;

/** Instruction to the mixer workers: start or stop mixing a room in the given mode. */
public record MixerAssignment(UUID roomId, MixerMode mode, MixerAction action) {
}
