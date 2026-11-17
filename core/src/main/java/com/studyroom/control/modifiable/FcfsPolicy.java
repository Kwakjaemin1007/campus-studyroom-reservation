package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 선착순: 묶음에서 가장 먼저 도착한 요청 하나를 확정한다. */
public final class FcfsPolicy implements IAllocationPolicy {
    public static final String NAME = "FCFS";
    /** 하루 최대 1시간 (1시간 슬롯 1개). */
    public static final int DEFAULT_MAX_SLOTS_PER_DAY = 1;

    private final int maxSlotsPerDay;

    public FcfsPolicy() {
        this(DEFAULT_MAX_SLOTS_PER_DAY);
    }

    public FcfsPolicy(int maxSlotsPerDay) {
        this.maxSlotsPerDay = maxSlotsPerDay;
    }

    @Override
    public int maxSlotsPerDay() {
        return maxSlotsPerDay;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<AllocationDecision> allocate(List<ReservationRequest> requests, SlotState slotState) {
        ReservationRequest winner = slotState.taken() ? null
                : requests.stream().min(Comparator.comparingLong(ReservationRequest::sequence)).orElse(null);
        List<AllocationDecision> out = new ArrayList<>(requests.size());
        for (ReservationRequest q : requests) {
            out.add(q == winner ? AllocationDecision.confirm(q.id(), 0)
                    : AllocationDecision.reject(q.id(), slotState.taken() ? "SLOT_TAKEN" : "LOST_FCFS"));
        }
        return out;
    }
}
