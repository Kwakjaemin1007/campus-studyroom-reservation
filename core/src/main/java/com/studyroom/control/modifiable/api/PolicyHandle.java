package com.studyroom.control.modifiable.api;

/** 현재 정책과 그 버전. 버전은 교체할 때마다 1씩 오르고 예약마다 policy_version으로 기록된다. */
public record PolicyHandle(IAllocationPolicy policy, int version) {
    public String name() {
        return policy.name();
    }
}
