package com.studyroom.control.modifiable.api;

import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotState;

import java.util.List;

/**
 * 배정 정책 (Strategy).
 *
 * 경계 계약: allocate()는 10ms 안에 끝나고 I/O(DB·네트워크)를 하지 않는다.
 * 워커가 슬롯 락을 잡은 채 부르기 때문이다. 판단에 필요한 값은 전부 인자로 받는다.
 */
public interface IAllocationPolicy {
    /** Registry에 등록되는 이름. 관리자 API에서 이 이름으로 교체를 요청한다. */
    String name();

    default AllocationTiming timing() {
        return AllocationTiming.IMMEDIATE;
    }

    /**
     * 한 슬롯에 모인 요청 묶음을 받아 요청마다 확정/거절을 돌려준다.
     * 반환 목록은 requests의 모든 요청을 한 번씩 포함해야 한다.
     */
    List<AllocationDecision> allocate(List<ReservationRequest> requests, SlotState slotState);
}
