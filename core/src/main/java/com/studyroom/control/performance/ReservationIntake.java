package com.studyroom.control.performance;

import com.studyroom.domain.PartyMember;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.studyroom.control.performance.api.*;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.modifiable.api.AllocationTiming;
import com.studyroom.control.modifiable.api.IBookingRules;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.IPolicySwitch;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.SwitchPhase;
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
    private final IPolicyProvider policies;
    private final IPolicySwitch policySwitch;
    private final IBookingRules rules;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong degradedCount = new AtomicLong();

    /**
     * @param minPartySize 신청자 포함 최소 인원(회의 결정 11/19: 2명)
     * @param policies     지금 정책. 접수 시점 버전을 신청에 찍고, 입찰을 안 쓰는 정책이면 입찰액을 0으로 정리한다
     * @param policySwitch 전환이 진행 중이면 마감형 신청을 잠시 받지 않는다(보류가 0이 되어야 전환할 수 있으므로)
     */
    public ReservationIntake(IAdmissionQueue queue, ISlotStatus slotStatus, IReservationLifecycle lifecycle, Clock clock,
                             int minPartySize, IPolicyProvider policies, IPolicySwitch policySwitch) {
        this(queue, slotStatus, lifecycle, clock, minPartySize, policies, policySwitch, null);
    }

    /** @param rules 운영 시간·시험 기간 예약 규칙. null이면 검사하지 않는다 */
    public ReservationIntake(IAdmissionQueue queue, ISlotStatus slotStatus, IReservationLifecycle lifecycle, Clock clock,
                             int minPartySize, IPolicyProvider policies, IPolicySwitch policySwitch, IBookingRules rules) {
        this.rules = rules;
        this.minPartySize = minPartySize;
        this.queue = queue;
        this.slotStatus = slotStatus;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.policies = policies;
        this.policySwitch = policySwitch;
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

        // 정책 확인: 앱이 본 규칙과 지금 규칙이 같을 때만 받는다.
        // 운영 시간과 시험 기간 예약 규칙. 규칙 내용은 변경 용이성 쪽(BookingRules)에 있다.
        if (rules != null) rules.check(c.slot(), clock.instant());
        PolicyHandle h = policies.current(c.slot());   // 자리 날짜의 정책
        if (c.expectedPolicyVersion() != null && c.expectedPolicyVersion() != h.version()) {
            throw new PolicyChangedException(PolicyChangedException.POLICY_CHANGED,
                    "배정 정책이 " + h.name() + "(으)로 바뀌었습니다. 새 규칙으로 다시 신청해 주세요");
        }
        // 평소 정책 전환은 평소 정책을 쓰는 자리의 보류만 기다린다. 시험 기간 날짜는 달력이 정하므로 영향이 없다.
        boolean usesRegularPolicy = h.name().equals(policies.current().name());
        if (usesRegularPolicy && h.policy().timing() == AllocationTiming.AT_CLOSE && switchPending()) {
            throw new PolicyChangedException(PolicyChangedException.POLICY_SWITCHING,
                    "배정 정책을 바꾸는 중이라 잠시 신청을 받지 않습니다");
        }
        // 입찰을 안 쓰는 정책이면 입찰액을 버린다. 그대로 두면 크레딧이 마감까지 묶인다.
        int bid = h.policy().usesBid() ? c.bidAmount() : 0;
        List<PartyMember> party = partyOf(c, bid);

        // 조기 거절: 마감된 슬롯은 줄에 넣지 않는다. 캐시가 죽었으면 건너뛴다(QAS-5).
        try {
            if (slotStatus.isTaken(c.slot())) throw new SlotClosedException(c.slot().key());
        } catch (CacheUnavailableException e) {
            degradedCount.incrementAndGet();
        }

        // 팀 입찰액 = 1인당 입찰액 × 인원, 팀 우선순위 = 구성원 우선순위의 합(회의 결정 11/19).
        queue.enqueue(new ReservationRequest(id, idempotencyKey, c.userId(), c.slot(),
                clock.instant(), sequence.incrementAndGet(),
                party.stream().mapToInt(PartyMember::bid).sum(),
                party.stream().mapToInt(PartyMember::priority).sum(), party).withPolicy(h.name(), h.version()));
        return Ticket.pending(id);
    }

    /**
     * 신청자 + 동행 학번으로 구성원을 만든다. 최소 인원, 학번 중복을 여기서 거절한다(400).
     * 구성원 전원이 1인당 입찰액(bid)을 똑같이 건다. 동행이 따로 적은 금액은 쓰지 않는다.
     * 같은 시간 다른 예약과의 중복, 하루 한도, 크레딧 잔액은 상태를 봐야 하므로 배정 단계에서 검사한다.
     */
    private List<PartyMember> partyOf(SubmitCommand c, int bid) {
        List<PartyMember> party = new ArrayList<>();
        party.add(new PartyMember(c.userId(), bid, c.priority()));
        Set<String> seen = new HashSet<>();
        seen.add(c.userId());
        for (PartyMember m : c.companions()) {
            if (!seen.add(m.userId())) throw new IllegalArgumentException("duplicate member: " + m.userId());
            party.add(new PartyMember(m.userId(), bid, m.priority()));
        }
        if (party.size() < minPartySize) {
            throw new IllegalArgumentException("at least " + minPartySize + " members are required (with the requester)");
        }
        return party;
    }

    /**
     * 전환을 기다리는 중인가. 상태 저장소(Redis)가 죽었으면 아니라고 본다(접수를 멈추지 않는다, QAS-5).
     * 그사이 보류가 생겨도 전환기가 쓰기 락을 잡은 뒤 보류를 다시 확인해 전환을 취소하므로 규칙이 섞이지 않는다.
     */
    private boolean switchPending() {
        try {
            return policySwitch.phase() != SwitchPhase.ACTIVE;
        } catch (RuntimeException e) {
            degradedCount.incrementAndGet();
            return false;
        }
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
