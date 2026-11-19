package com.studyroom.control.modifiable.api;

import java.time.Instant;
import java.util.Optional;

/**
 * 전환 상태, 임대 만료 시각, 전환 기록을 보관한다(Device). compareAndSet이 동시 전환을 막는 CAS다.
 * 전환 기록도 여기 두어야 서버가 재시작되거나 여러 대여도 다른 서버에서 같은 switchId를 조회할 수 있다.
 */
public interface ISwitchStateStore {
    SwitchPhase get();

    boolean compareAndSet(SwitchPhase expect, SwitchPhase update);

    void setLeaseExpiry(Instant until);

    Instant leaseExpiry();

    /** 전환 기록을 저장(같은 switchId면 덮어쓰기) */
    void saveStatus(SwitchStatus status);

    Optional<SwitchStatus> findStatus(String switchId);
}
