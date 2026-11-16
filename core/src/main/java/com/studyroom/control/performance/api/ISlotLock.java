package com.studyroom.control.performance.api;

import java.time.Duration;

/** 슬롯 단위 분산 락. lease가 지나면 자동으로 풀리므로 최종 방어는 DB 유니크 제약이 맡는다. */
public interface ISlotLock {
    boolean tryLock(String slotKey, Duration lease);

    void unlock(String slotKey);
}
