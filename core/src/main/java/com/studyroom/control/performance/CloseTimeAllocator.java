package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.availability.api.IReservationLifecycle;
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
import java.util.List;

/**
 * 마감형 정책(우선순위·크레딧 베팅)의 일괄 배정.
 * 접수할 때는 요청을 보류(REQUESTED)로 저장하고 슬롯의 SLOT_CLOSE 지연 메시지를 건다(collect).
 * 그 메시지가 오면 슬롯에 보류된 요청 전부를 한 번에 판정한다(onSlotClose).
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

    CloseTimeAllocator(ISlotLock slotLock, IPolicyProvider policies, IReservationLifecycle lifecycle, IDelayedMessage delayed,
                       SlotDecider decider, Clock clock, Duration lockLease, Duration collectWindow) {
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
                List<ReservationRequest> held = lifecycle.heldRequests(slot);
                if (!held.isEmpty()) decider.decide(held, slot, lease.handle());
            } finally {
                slotLock.unlock(slotKey);
            }
        }
    }
}
