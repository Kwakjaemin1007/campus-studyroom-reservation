package com.studyroom.control.modifiable.api;

import java.time.Instant;

/** 전환 상태와 임대 만료 시각을 보관한다. compareAndSet이 동시 전환을 막는 CAS다. */
public interface ISwitchStateStore {
    SwitchPhase get();

    boolean compareAndSet(SwitchPhase expect, SwitchPhase update);

    void setLeaseExpiry(Instant until);

    Instant leaseExpiry();
}
