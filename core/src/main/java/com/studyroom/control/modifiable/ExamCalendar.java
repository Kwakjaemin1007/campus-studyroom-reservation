package com.studyroom.control.modifiable;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.ExamPeriod;
import com.studyroom.control.modifiable.api.IExamCalendar;
import com.studyroom.control.modifiable.api.IExamPeriodStore;
import com.studyroom.control.modifiable.api.SwitchConflictException;
import com.studyroom.control.modifiable.api.UnknownPolicyException;
import com.studyroom.domain.Reservation;
import com.studyroom.support.CampusTime;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * 시험 기간 달력 (11/25 결정).
 * 관리자가 학사 일정을 보고 "12/7~12/20 크레딧 베팅"을 등록하면 그 날짜의 자리는 언제 신청하든 베팅으로,
 * 나머지 날짜는 평소 정책(선착순)으로 배정한다. 새벽에 정책을 바꾸는 대신 자리 날짜로 고른다.
 *
 * 등록·취소는 정책 전환과 같은 쓰기 락을 잡고 버전을 올린다.
 * 그래서 등록 순간 배정 중이던 건은 옛 달력으로 끝나고, 큐에 있던 신청은 POLICY_CHANGED로 돌려보낸다.
 * 등록 전에 이미 잡힌 그 기간의 자리는 자동 취소하고 구성원에게 알린다.
 */
public final class ExamCalendar implements IExamCalendar {
    public static final String CANCEL_REASON = "EXAM_PERIOD";
    static final String CANCEL_MESSAGE = "시험 기간으로 지정되어 예약이 취소되었습니다. 이 날짜는 하루 전에 크레딧 베팅으로 다시 신청할 수 있어요";

    private final PolicyRegistry registry;
    private final IExamPeriodStore store;
    private final IReservationQuery reservations;
    private final IReservationLifecycle lifecycle;
    private final Clock clock;
    private final Duration lockWait;

    public ExamCalendar(PolicyRegistry registry, IExamPeriodStore store, IReservationQuery reservations,
                        IReservationLifecycle lifecycle, Clock clock, Duration lockWait) {
        this.registry = registry;
        this.store = store;
        this.reservations = reservations;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.lockWait = lockWait;
    }

    @Override
    public Registration register(LocalDate start, LocalDate end, String policy) {
        if (!registry.contains(policy)) throw new UnknownPolicyException(policy);
        LocalDate today = CampusTime.dayOf(clock.instant());
        if (!start.isAfter(today)) throw new IllegalArgumentException("시험 기간은 내일 이후로 등록할 수 있습니다");
        ExamPeriod period = new ExamPeriod("EX-" + UUID.randomUUID().toString().substring(0, 8), start, end, policy);
        for (ExamPeriod p : store.all()) {
            if (p.overlaps(period)) {
                throw new IllegalArgumentException("이미 등록된 시험 기간(" + p.start() + "~" + p.end() + ")과 겹칩니다");
            }
        }
        underWriteLock(() -> {
            store.save(period);
            registry.bumpVersion();
        });
        // 등록 전에 선착순으로 잡힌 자리. 쓰기 락 뒤라 이 사이에 새로 잡히는 일은 없다(새 신청은 시험 규칙을 탄다).
        List<Reservation> taken = reservations.confirmedBetween(CampusTime.startOfDay(start),
                CampusTime.startOfDay(end.plusDays(1)));
        int canceled = 0;
        for (Reservation r : taken) {
            if (lifecycle.cancel(r.id(), CANCEL_REASON, CANCEL_MESSAGE)
                    == IReservationLifecycle.TransitionResult.APPLIED) {
                canceled++;
            }
        }
        return new Registration(period, canceled);
    }

    @Override
    public boolean cancel(String id) {
        boolean[] removed = new boolean[1];
        underWriteLock(() -> {
            removed[0] = store.delete(id);
            if (removed[0]) registry.bumpVersion();
        });
        return removed[0];
    }

    @Override
    public List<ExamPeriod> upcoming() {
        LocalDate today = CampusTime.dayOf(clock.instant());
        return store.all().stream().filter(p -> !p.end().isBefore(today)).toList();
    }

    private void underWriteLock(Runnable change) {
        Lock w = registry.writeLock();
        boolean locked;
        try {
            locked = w.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SwitchConflictException("중단되었습니다");
        }
        if (!locked) throw new SwitchConflictException("배정이 끝나지 않아 지금은 바꿀 수 없습니다. 잠시 후 다시 시도해 주세요");
        try {
            change.run();
        } finally {
            w.unlock();
        }
    }
}
