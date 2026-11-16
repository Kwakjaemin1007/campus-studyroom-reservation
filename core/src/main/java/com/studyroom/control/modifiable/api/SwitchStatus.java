package com.studyroom.control.modifiable.api;

import java.time.Instant;

public record SwitchStatus(String switchId, String target, State state, int fromVersion, int toVersion,
                           String message, Instant requestedAt, Instant finishedAt) {
    public enum State { REQUESTED, DRAINING, COMPLETED, FAILED }

    public SwitchStatus with(State s, int to, String msg, Instant finished) {
        return new SwitchStatus(switchId, target, s, fromVersion, to, msg, requestedAt, finished);
    }
}
