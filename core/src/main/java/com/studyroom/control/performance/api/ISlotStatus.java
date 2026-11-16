package com.studyroom.control.performance.api;

import com.studyroom.domain.SlotId;

/**
 * 슬롯 마감 여부 캐시. 조기 거절(409)에만 쓰는 힌트이고 정답은 DB에 있다.
 * 캐시가 죽으면 CacheUnavailableException을 던지고, 호출자는 캐시 없이 계속 간다.
 */
public interface ISlotStatus {
    boolean isTaken(SlotId slot);

    void markTaken(SlotId slot);

    void release(SlotId slot);
}
