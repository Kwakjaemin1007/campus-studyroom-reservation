package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 크레딧 베팅: 접수 창이 닫힌 뒤 최고 입찰을 확정한다.
 * 확정 규칙: 정수 1크레딧 단위, 비공개(봉인) 입찰, 동점은 먼저 입찰한 사람.
 * 낙찰가 방식(자기 입찰가 / 차순위 금액)은 팀 미정이라 생성자에서 고른다.
 */
public final class CreditBettingPolicy implements IAllocationPolicy {
    public static final String NAME = "CREDIT_BETTING";

    public enum Pricing { FIRST_PRICE, SECOND_PRICE }

    private final Pricing pricing;

    public CreditBettingPolicy() {
        this(Pricing.FIRST_PRICE);
    }

    public CreditBettingPolicy(Pricing pricing) {
        this.pricing = pricing;
    }

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
        List<ReservationRequest> ranked = requests.stream()
                .filter(q -> q.bidAmount() >= 1)
                .sorted(Comparator.comparingInt(ReservationRequest::bidAmount).reversed()
                        .thenComparingLong(ReservationRequest::sequence))
                .toList();
        ReservationRequest winner = slotState.taken() || ranked.isEmpty() ? null : ranked.get(0);
        int charge = 0;
        if (winner != null) {
            // 차순위 금액 방식에서 입찰자가 혼자면 최소 단위인 1크레딧을 낸다.
            charge = pricing == Pricing.FIRST_PRICE ? winner.bidAmount()
                    : (ranked.size() >= 2 ? ranked.get(1).bidAmount() : 1);
        }
        List<AllocationDecision> out = new ArrayList<>(requests.size());
        for (ReservationRequest q : requests) {
            if (q == winner) out.add(AllocationDecision.confirm(q.id(), charge));
            else if (q.bidAmount() < 1) out.add(AllocationDecision.reject(q.id(), "NO_BID"));
            else out.add(AllocationDecision.reject(q.id(), slotState.taken() ? "SLOT_TAKEN" : "OUTBID"));
        }
        return out;
    }
}
