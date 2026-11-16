package com.studyroom.control.performance;

import com.studyroom.control.performance.api.*;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.Ticket;
import com.studyroom.support.Ids;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 접수: 검증 → 멱등키 확인 → 조기 거절 → 큐 적재 → 티켓.
 * 여기까지가 접수 p95 300ms 예산이다. 배정은 큐 뒤의 워커가 한다.
 */
public final class ReservationIntake implements IReservationIntake {
    private final IAdmissionQueue queue;
    private final ISlotStatus slotStatus;
    private final IReservationLifecycle lifecycle;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong degradedCount = new AtomicLong();

    public ReservationIntake(IAdmissionQueue queue, ISlotStatus slotStatus,
                             IReservationLifecycle lifecycle, Clock clock) {
        this.queue = queue;
        this.slotStatus = slotStatus;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Override
    public Ticket submit(SubmitCommand c, String idempotencyKey) {
        if (c.userId() == null || c.userId().isBlank()) throw new IllegalArgumentException("userId is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        if (c.slot() == null) throw new IllegalArgumentException("slot is required");
        if (c.bidAmount() < 0) throw new IllegalArgumentException("bidAmount must be >= 0");

        String id = Ids.requestId(c.userId(), idempotencyKey);

        // 멱등키: 이미 결과가 기록된 요청이면 그 결과를 그대로 돌려준다(DB 영구 기록).
        Optional<Reservation> existing = lifecycle.find(id);
        if (existing.isPresent()) return Ticket.of(existing.get());

        // 조기 거절: 마감된 슬롯은 줄에 넣지 않는다. 캐시가 죽었으면 건너뛴다(QAS-5).
        try {
            if (slotStatus.isTaken(c.slot())) throw new SlotClosedException(c.slot().key());
        } catch (CacheUnavailableException e) {
            degradedCount.incrementAndGet();
        }

        queue.enqueue(new ReservationRequest(id, idempotencyKey, c.userId(), c.slot(),
                clock.instant(), sequence.incrementAndGet(), c.bidAmount(), c.priority()));
        return Ticket.pending(id);
    }

    @Override
    public Ticket getTicket(String ticketId) {
        return lifecycle.find(ticketId).map(Ticket::of).orElseGet(() -> Ticket.pending(ticketId));
    }

    /** 캐시 장애로 조기 거절을 건너뛴 횟수(관측용). */
    public long degradedCount() {
        return degradedCount.get();
    }
}
