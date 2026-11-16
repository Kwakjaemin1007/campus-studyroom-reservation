package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.ISwitchStateStore;
import com.studyroom.control.modifiable.api.SwitchPhase;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemorySwitchStateStore implements ISwitchStateStore {
    private final AtomicReference<SwitchPhase> phase = new AtomicReference<>(SwitchPhase.ACTIVE);
    private volatile Instant leaseExpiry;

    @Override
    public SwitchPhase get() {
        return phase.get();
    }

    @Override
    public boolean compareAndSet(SwitchPhase expect, SwitchPhase update) {
        return phase.compareAndSet(expect, update);
    }

    @Override
    public void setLeaseExpiry(Instant until) {
        this.leaseExpiry = until;
    }

    @Override
    public Instant leaseExpiry() {
        return leaseExpiry;
    }
}
