package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 우선순위: 접수 창이 닫힌 뒤 우선순위가 가장 높은 요청을 확정한다. 동점은 먼저 온 요청. */
public final class PriorityPolicy implements IAllocationPolicy {
    public static final String NAME = "PRIORITY";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AllocationTiming timing() {
        return AllocationTiming.AT_CLOSE;
    }

    @Override
    public List<AllocationDecision> allocate(List<ReservationRequest> requests, SlotState slotState) {
        ReservationRequest winner = slotState.taken() ? null : requests.stream()
                .min(Comparator.comparingInt(ReservationRequest::priority).reversed()
                        .thenComparingLong(ReservationRequest::sequence))
                .orElse(null);
        List<AllocationDecision> out = new ArrayList<>(requests.size());
        for (ReservationRequest q : requests) {
            out.add(q == winner ? AllocationDecision.confirm(q.id(), 0)
                    : AllocationDecision.reject(q.id(), slotState.taken() ? "SLOT_TAKEN" : "LOWER_PRIORITY"));
        }
        return out;
    }
}
