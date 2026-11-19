package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotState;

import java.util.ArrayList;
import java.util.List;

/**
 * 우선순위: 접수 창이 닫힌 뒤 팀 우선순위(구성원 우선순위의 합)가 가장 높은 요청을 확정한다.
 * 합계가 같으면 패널티가 적은 팀, 그다음 인원이 많은 팀, 그다음 먼저 온 팀(TeamRanking).
 */
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
                .min(TeamRanking.by(ReservationRequest::priority))
                .orElse(null);
        List<AllocationDecision> out = new ArrayList<>(requests.size());
        for (ReservationRequest q : requests) {
            out.add(q == winner ? AllocationDecision.confirm(q.id(), 0)
                    : AllocationDecision.reject(q.id(), slotState.taken() ? "SLOT_TAKEN" : "LOWER_PRIORITY"));
        }
        return out;
    }
}
