package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.performance.api.ISlotLock;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.support.CampusTime;

import com.studyroom.control.modifiable.api.IBookingRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * 구성원 검사(11/19 회의 규칙). 함께 신청한 사람 전원에 대해
 *  1. 같은 시간에 다른 진행 중 예약(접수 중·확정·입실)에 들어가 있으면 거절: MEMBER_TIME_CONFLICT
 *  2. 정책에 하루 한도가 있으면 사람마다 센다: DAILY_LIMIT
 *  3. 시작 전 예약(접수 중·확정)을 한도(11/26 결정: 3개)만큼 들고 있으면 거절: PENDING_LIMIT
 * 같은 사람이 든 요청이 여러 워커에서 동시에 처리돼도 어긋나지 않게, 구성원마다 사람 락(member:{학번})을
 * 학번 순서로 잡은 뒤 센다. 순서를 고정하므로 두 요청이 서로의 락을 기다리는 교착이 생기지 않는다.
 * 3번은 날짜를 넘나드는 한도라 락을 날짜별(member:{학번}@{날짜})이 아니라 사람 단위로 잡는다. 같은 사람의 요청끼리만 부딪힌다.
 * 하루 한도 값은 정책(IAllocationPolicy.maxSlotsPerDay), 보유 한도 값은 예약 규칙(IBookingRules)이 갖고, 여기서는 세고 막기만 한다.
 */
final class PartyGuard {
    enum Verdict { BUSY, REJECT, PASS }

    static final String DAILY_LIMIT = "DAILY_LIMIT";
    static final String MEMBER_TIME_CONFLICT = "MEMBER_TIME_CONFLICT";
    static final String PENDING_LIMIT = "PENDING_LIMIT";

    /** 검사 결과. 잡은 락은 close가 푼다. try-with-resources로 쓴다. */
    final class Pass implements AutoCloseable {
        final Verdict verdict;
        final String reason;
        private final List<String> keys;

        private Pass(Verdict verdict, String reason, List<String> keys) {
            this.verdict = verdict;
            this.reason = reason;
            this.keys = keys;
        }

        @Override
        public void close() {
            unlockAll(keys);
        }
    }

    /**
     * 같은 사람이 든 요청이 동시에 올 때만 부딪히는 락이다. 잡고 있는 시간이 확정 한 건(수십 ms)이라 잠깐 기다린다.
     * 곧바로 nack하면 수신 횟수가 소진돼 DLQ로 가 버리므로, 정말 못 잡을 때만 BUSY를 돌려준다.
     */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(1);

    private final ISlotLock slotLock;
    private final IReservationLifecycle lifecycle;
    private final IReservationQuery reservations;
    private final Duration lockLease;
    private final IBookingRules rules;
    private final Clock clock;

    /** @param rules 보유 한도. null이면 세지 않는다 */
    PartyGuard(ISlotLock slotLock, IReservationLifecycle lifecycle, IReservationQuery reservations, Duration lockLease,
               IBookingRules rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
        this.slotLock = slotLock;
        this.lifecycle = lifecycle;
        this.reservations = reservations;
        this.lockLease = lockLease;
    }

    Pass enter(ReservationRequest q, PolicyHandle h) {
        LocalDate day = CampusTime.dayOf(q.slot().startsAt());
        List<String> members = q.memberIds().stream().sorted().toList();
        List<String> held = new ArrayList<>(members.size());
        for (String m : members) {
            String key = "member:" + m;
            if (!lockWithWait(key)) {
                unlockAll(held);
                return new Pass(Verdict.BUSY, null, List.of());
            }
            held.add(key);
        }
        try {
            // 이미 기록된 요청(큐 재전달)은 자기 자신과 겹친다고 보지 않도록 통과시킨다.
            if (lifecycle.find(q.id()).isPresent()) return new Pass(Verdict.PASS, null, held);
            int max = h.policy().maxSlotsPerDay();
            int maxPending = rules == null ? IBookingRules.NO_LIMIT : rules.maxPendingPerPerson();
            Instant now = clock.instant();
            for (String m : members) {
                if (reservations.activeAt(m, q.slot().startsAt()).isPresent()) {
                    return new Pass(Verdict.REJECT, MEMBER_TIME_CONFLICT, held);
                }
                if (max != IAllocationPolicy.UNLIMITED && reservations.slotsUsedOnDay(m, day) >= max) {
                    return new Pass(Verdict.REJECT, DAILY_LIMIT, held);
                }
                if (maxPending != IBookingRules.NO_LIMIT && reservations.pendingCount(m, now) >= maxPending) {
                    return new Pass(Verdict.REJECT, PENDING_LIMIT, held);
                }
            }
            return new Pass(Verdict.PASS, null, held);
        } catch (RuntimeException e) {
            unlockAll(held); // 세다가 실패하면 락을 바로 푼다
            throw e;
        }
    }

    private void unlockAll(List<String> keys) {
        for (String k : keys) slotLock.unlock(k);
    }

    private boolean lockWithWait(String key) {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (true) {
            if (slotLock.tryLock(key, lockLease)) return true;
            if (System.nanoTime() >= deadline) return false;
            LockSupport.parkNanos(2_000_000);
        }
    }
}
