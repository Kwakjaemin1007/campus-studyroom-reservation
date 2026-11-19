package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.performance.api.CacheUnavailableException;
import com.studyroom.control.performance.api.ISlotStatus;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.SlotState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 슬롯의 요청들을 정책에 넘겨 판정하고, 결과대로 확정·거절을 요청한다.
 * 즉시 판정(선착순)과 마감 일괄 판정(우선순위·베팅)이 같은 이 코드를 쓴다. 호출하는 쪽이 슬롯 락을 잡고 부른다.
 */
final class SlotDecider {
    private final ISlotStatus slotStatus;
    private final IReservationLifecycle lifecycle;

    SlotDecider(ISlotStatus slotStatus, IReservationLifecycle lifecycle) {
        this.slotStatus = slotStatus;
        this.lifecycle = lifecycle;
    }

    void decide(List<ReservationRequest> requests, SlotId slot, PolicyHandle h) {
        Map<String, ReservationRequest> byId = new HashMap<>();
        for (ReservationRequest q : requests) byId.put(q.id(), q);
        List<AllocationDecision> decisions = h.policy().allocate(requests, new SlotState(slot, isTakenOrUnknown(slot)));
        for (AllocationDecision d : decisions) {
            ReservationRequest q = byId.get(d.requestId());
            if (q == null) continue;
            if (d.outcome() == AllocationDecision.Outcome.CONFIRM) {
                IReservationLifecycle.ConfirmResult r = lifecycle.confirm(q, d, h.version(), h.name());
                if (r == IReservationLifecycle.ConfirmResult.CONFIRMED || r == IReservationLifecycle.ConfirmResult.SLOT_TAKEN) {
                    markTakenQuietly(slot);
                }
            } else {
                lifecycle.reject(q, d.reason(), h.version(), h.name());
            }
        }
    }

    /** 캐시가 죽었으면 "비어 있다"고 보고 진행한다. 틀렸다면 DB 유니크 제약이 막는다. */
    private boolean isTakenOrUnknown(SlotId slot) {
        try {
            return slotStatus.isTaken(slot);
        } catch (CacheUnavailableException e) {
            return false;
        }
    }

    private void markTakenQuietly(SlotId slot) {
        try {
            slotStatus.markTaken(slot);
        } catch (CacheUnavailableException ignored) {
            // 캐시는 힌트일 뿐이다.
        }
    }
}
