package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.performance.api.ISlotLock;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.support.CampusTime;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.locks.LockSupport;

/**
 * 하루 예약 한도 검사. 한도 값은 정책(IAllocationPolicy.maxSlotsPerDay)이 갖고, 여기서는 세고 막기만 한다.
 * 같은 사람의 요청이 여러 워커에서 동시에 처리돼도 한도를 넘지 않게 사람·날짜별 락(quota:{userId}@{날짜}) 안에서 센다.
 */
final class DailyQuotaGuard {
    enum Verdict { NO_LIMIT, BUSY, OVER_LIMIT, WITHIN_LIMIT }

    /** 검사 결과. 락을 잡았으면 close가 푼다. try-with-resources로 쓴다. */
    final class Pass implements AutoCloseable {
        final Verdict verdict;
        private final String key;

        private Pass(Verdict verdict, String key) {
            this.verdict = verdict;
            this.key = key;
        }

        @Override
        public void close() {
            if (key != null) slotLock.unlock(key);
        }
    }

    /**
     * 같은 사람의 요청이 동시에 올 때만 부딪히는 락이다. 잡고 있는 시간이 확정 한 건(수십 ms)이라 잠깐 기다린다.
     * 곧바로 nack하면 수신 횟수가 소진돼 DLQ로 가 버리므로, 정말 못 잡을 때만 BUSY를 돌려준다.
     */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(1);

    private final ISlotLock slotLock;
    private final IReservationLifecycle lifecycle;
    private final IReservationQuery reservations;
    private final Duration lockLease;

    DailyQuotaGuard(ISlotLock slotLock, IReservationLifecycle lifecycle, IReservationQuery reservations, Duration lockLease) {
        this.slotLock = slotLock;
        this.lifecycle = lifecycle;
        this.reservations = reservations;
        this.lockLease = lockLease;
    }

    Pass enter(ReservationRequest q, PolicyHandle h) {
        int max = h.policy().maxSlotsPerDay();
        if (max == IAllocationPolicy.UNLIMITED) return new Pass(Verdict.NO_LIMIT, null);
        LocalDate day = CampusTime.dayOf(q.slot().startsAt());
        String key = "quota:" + q.userId() + "@" + day;
        if (!lockWithWait(key)) return new Pass(Verdict.BUSY, null);
        try {
            // 이미 기록된 요청(큐 재전달)은 자기 자신을 세지 않도록 통과시킨다.
            boolean over = lifecycle.find(q.id()).isEmpty() && reservations.slotsUsedOnDay(q.userId(), day) >= max;
            return new Pass(over ? Verdict.OVER_LIMIT : Verdict.WITHIN_LIMIT, key);
        } catch (RuntimeException e) {
            slotLock.unlock(key); // 세다가 실패하면 락을 바로 푼다
            throw e;
        }
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
