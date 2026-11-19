package com.studyroom.control.performance;

import com.studyroom.domain.PartyMember;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final int minPartySize;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong degradedCount = new AtomicLong();

    public ReservationIntake(IAdmissionQueue queue, ISlotStatus slotStatus,
                             IReservationLifecycle lifecycle, Clock clock) {
        this(queue, slotStatus, lifecycle, clock, 1);
    }

    /** @param minPartySize 신청자 포함 최소 인원(회의 결정 11/19: 2명) */
    public ReservationIntake(IAdmissionQueue queue, ISlotStatus slotStatus,
                             IReservationLifecycle lifecycle, Clock clock, int minPartySize) {
        this.minPartySize = minPartySize;
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
        List<PartyMember> party = partyOf(c);

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
                clock.instant(), sequence.incrementAndGet(),
                party.stream().mapToInt(PartyMember::bid).sum(), c.priority(), party));
        return Ticket.pending(id);
    }

    /**
     * 신청자 + 동행 학번으로 구성원을 만든다. 최소 인원, 학번 중복을 여기서 거절한다(400).
     * 같은 시간 다른 예약과의 중복, 하루 한도, 크레딧 잔액은 상태를 봐야 하므로 배정 단계에서 검사한다.
     */
    private List<PartyMember> partyOf(SubmitCommand c) {
        List<PartyMember> party = new ArrayList<>();
        party.add(new PartyMember(c.userId(), c.bidAmount()));
        Set<String> seen = new HashSet<>();
        seen.add(c.userId());
        for (PartyMember m : c.companions()) {
            if (!seen.add(m.userId())) throw new IllegalArgumentException("duplicate member: " + m.userId());
            party.add(m);
        }
        if (party.size() < minPartySize) {
            throw new IllegalArgumentException("at least " + minPartySize + " members are required (with the requester)");
        }
        return party;
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
