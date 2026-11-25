package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.PolicyLease;
import com.studyroom.control.performance.api.ISlotLock;
import com.studyroom.domain.DelayedMessage;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 마감형 정책(우선순위·크레딧 베팅)의 일괄 배정.
 * 접수할 때는 요청을 보류(REQUESTED)로 저장하고 슬롯의 SLOT_CLOSE 지연 메시지를 건다(collect).
 * 그 메시지가 오면 슬롯에 보류된 요청 전부를 한 번에 판정한다(onSlotClose).
 * 판정 직전에 요청마다 팀 패널티(구성원의 이번 학기 노쇼 횟수 합)를 채운다. 정책은 I/O를 하지 않으므로 여기서 넘겨준다.
 * 조회는 (요청 수 × 인원) 번이지만 접수 경로가 아니라 마감 시 한 번이라 접수 300ms 예산에 들어가지 않는다.
 */
final class CloseTimeAllocator {
    private final ISlotLock slotLock;
    private final IPolicyProvider policies;
    private final IReservationLifecycle lifecycle;
    private final IDelayedMessage delayed;
    private final SlotDecider decider;
    private final Clock clock;
    private final Duration lockLease;
    private final Duration collectWindow;
    private final IReservationQuery reservations;
    private final ICreditPeriod period;

    CloseTimeAllocator(ISlotLock slotLock, IPolicyProvider policies, IReservationLifecycle lifecycle, IDelayedMessage delayed,
                       SlotDecider decider, Clock clock, Duration lockLease, Duration collectWindow,
                       IReservationQuery reservations, ICreditPeriod period) {
        this.reservations = reservations;
        this.period = period;
        this.slotLock = slotLock;
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.delayed = delayed;
        this.decider = decider;
        this.clock = clock;
        this.lockLease = lockLease;
        this.collectWindow = collectWindow;
    }

    void collect(ReservationRequest q, PolicyHandle h) {
        lifecycle.hold(q, h.version(), h.name());
        Instant closeAt = clock.instant().plus(collectWindow);
        if (closeAt.isAfter(q.slot().startsAt())) closeAt = q.slot().startsAt();
        // 같은 키로 여러 번 걸어도 처음 것만 남는다(멱등).
        delayed.schedule("slot-close:" + q.slot().key(), closeAt,
                new DelayedMessage(DelayedMessage.Type.SLOT_CLOSE, q.slot().key()));
    }

    void onSlotClose(String slotKey) {
        SlotId slot = SlotId.parse(slotKey);
        try (PolicyLease lease = policies.lease()) {
            if (!slotLock.tryLock(slotKey, lockLease)) throw new IllegalStateException("slot is busy: " + slotKey);
            try {
                PolicyHandle h = lease.handle();
                List<ReservationRequest> held = new ArrayList<>();
                for (ReservationRequest q : lifecycle.heldRequests(slot)) {
                    // 보류 중에는 전환을 막지만, 그래도 다른 버전으로 모인 요청은 새 규칙으로 판정하지 않는다(환불).
                    if (q.policyVersion() != 0 && q.policyVersion() != h.version()) {
                        lifecycle.reject(q, AllocationProcessor.POLICY_CHANGED, h.version(), h.name());
                    } else {
                        held.add(q);
                    }
                }
                if (!held.isEmpty()) decider.decide(withPenalties(held), slot, h);
            } finally {
                slotLock.unlock(slotKey);
            }
        }
    }

    private List<ReservationRequest> withPenalties(List<ReservationRequest> held) {
        Instant now = clock.instant();
        Instant from = period.startOf(now);
        return held.stream().map(q -> q.withTeamPenalty(q.memberIds().stream()
                .mapToInt(m -> reservations.noShowsBetween(m, from, now)).sum())).toList();
    }
}
