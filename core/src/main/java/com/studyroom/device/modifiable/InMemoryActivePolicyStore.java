package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.ActivePolicy;
import com.studyroom.control.modifiable.api.IActivePolicyStore;

public final class InMemoryActivePolicyStore implements IActivePolicyStore {
    private volatile ActivePolicy active;

    @Override
    public ActivePolicy get() {
        return active;
    }

    @Override
    public void set(ActivePolicy active) {
        this.active = active;
    }
}
