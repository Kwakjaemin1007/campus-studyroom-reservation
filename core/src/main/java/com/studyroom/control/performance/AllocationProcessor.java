package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.AllocationTiming;
import com.studyroom.control.modifiable.api.IBookingRules;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.PolicyLease;
import com.studyroom.control.performance.api.*;
import com.studyroom.domain.ReservationRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 큐 소비자(경쟁 소비자 N개): 큐 수신 → 구성원 검사(같은 시간 중복·하루 한도) → 슬롯 락 → 정책 판단 → 확정/거절 요청 → 락 해제.
 * 확정 건당 100ms 예산. 정책이 무엇인지는 모르고 IAllocationPolicy만 부른다.
 * 여기에는 "소비 루프와 정책 전환용 관문(pause/resume/처리 중 건수)"만 두고, 나머지는 같은 구역의 세 클래스에 맡긴다.
 *  - PartyGuard: 구성원 검사(같은 시간 중복 예약 금지, 사람별 하루 한도)
 *  - SlotDecider: 한 슬롯의 판정과 확정·거절 요청
 *  - CloseTimeAllocator: 마감형 정책의 보류와 SLOT_CLOSE 일괄 배정(판정 직전에 팀 패널티를 채운다)
 */
public final class AllocationProcessor implements IAllocationGate, AutoCloseable {
    /** 거절 사유: 구성원 중 누군가 하루 한도를 넘음. 화면이 이 값으로 안내 문구를 고른다. */
    public static final String DAILY_LIMIT = PartyGuard.DAILY_LIMIT;
    /** 거절 사유: 구성원 중 누군가 같은 시간에 다른 예약에 들어가 있음 */
    public static final String MEMBER_TIME_CONFLICT = PartyGuard.MEMBER_TIME_CONFLICT;
    /** 시작 전 예약을 이미 한도(3개)만큼 들고 있다 */
    public static final String PENDING_LIMIT = PartyGuard.PENDING_LIMIT;
    /** 접수한 뒤 정책이 바뀌어 새 규칙으로 판정하지 않고 돌려보냈다. 앱은 정책을 다시 받아 새로 신청한다 */
    public static final String POLICY_CHANGED = "POLICY_CHANGED";

    private final IAdmissionQueue queue;
    private final ISlotLock slotLock;
    private final IPolicyProvider policies;
    private final IReservationLifecycle lifecycle;
    private final PartyGuard party;
    private final SlotDecider decider;
    private final CloseTimeAllocator closeTime;
    private final int workers;
    private final Duration lockLease;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong processed = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean paused;
    private volatile boolean running;

    public AllocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, ISlotStatus slotStatus,
                               IPolicyProvider policies, IReservationLifecycle lifecycle,
                               IReservationQuery reservations, IDelayedMessage delayed, Clock clock,
                               int workers, Duration lockLease, Duration collectWindow) {
        this(queue, slotLock, slotStatus, policies, lifecycle, reservations, delayed, clock, workers, lockLease, collectWindow,
                ICreditPeriod.FOREVER);
    }

    /** @param period 노쇼 패널티를 세는 기간(학기) */
    public AllocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, ISlotStatus slotStatus,
                               IPolicyProvider policies, IReservationLifecycle lifecycle,
                               IReservationQuery reservations, IDelayedMessage delayed, Clock clock,
                               int workers, Duration lockLease, Duration collectWindow, ICreditPeriod period) {
        this(queue, slotLock, slotStatus, policies, lifecycle, reservations, delayed, clock, workers, lockLease, collectWindow,
                period, null);
    }

    /** @param rules 마감형 신청을 언제 판정할지(시험 기간 내일 자리는 운영 종료에 일괄). null이면 collectWindow 뒤 */
    public AllocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, ISlotStatus slotStatus,
                               IPolicyProvider policies, IReservationLifecycle lifecycle,
                               IReservationQuery reservations, IDelayedMessage delayed, Clock clock,
                               int workers, Duration lockLease, Duration collectWindow, ICreditPeriod period,
                               IBookingRules rules) {
        this.queue = queue;
        this.slotLock = slotLock;
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.workers = workers;
        this.lockLease = lockLease;
        this.party = new PartyGuard(slotLock, lifecycle, reservations, lockLease, rules, clock);
        this.decider = new SlotDecider(slotStatus, lifecycle);
        this.closeTime = new CloseTimeAllocator(slotLock, policies, lifecycle, delayed, decider, clock, lockLease, collectWindow,
                reservations, period, rules);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(this::loop, "alloc-worker-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    private void loop() {
        while (running) {
            if (paused) {
                LockSupport.parkNanos(1_000_000);
                continue;
            }
            List<QueueMessage> batch;
            try {
                batch = queue.poll(1);
            } catch (RuntimeException e) {
                LockSupport.parkNanos(10_000_000);
                continue;
            }
            for (QueueMessage m : batch) process(m);
        }
    }

    private void process(QueueMessage m) {
        inFlight.incrementAndGet();
        // 읽기 락: 이 배정이 끝날 때까지 정책이 바뀌지 않는다.
        try (PolicyLease lease = policies.lease(m.request().slot(), m.request().requestedAt())) {   // 자리 날짜·신청 시각의 정책
            PolicyHandle h = lease.handle();
            ReservationRequest q = m.request();
            // 접수한 뒤 정책이 바뀌었으면 새 규칙으로 판정하지 않는다(선착순으로 낸 신청은 입찰이 없다).
            if (q.policyChangedSince(h.name(), h.version())) {
                lifecycle.reject(q, POLICY_CHANGED, h.version(), h.name());
                queue.ack(m);
                processed.incrementAndGet();
                return;
            }
            decideUnderLease(m, q, h);
        } catch (RuntimeException e) {
            queue.nack(m);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void decideUnderLease(QueueMessage m, ReservationRequest q, PolicyHandle h) {
        try (PartyGuard.Pass pass = party.enter(q, h)) {
            switch (pass.verdict) {
                case BUSY -> {
                    queue.nack(m);
                    return;
                }
                case REJECT -> {
                    lifecycle.reject(q, pass.reason, h.version(), h.name());
                    queue.ack(m);
                    processed.incrementAndGet();
                    return;
                }
                default -> {
                }
            }
            if (h.policy().timing() == AllocationTiming.AT_CLOSE) {
                closeTime.collect(q, h);
                queue.ack(m);
                processed.incrementAndGet();
                return;
            }
            String key = q.slot().key();
            if (!slotLock.tryLock(key, lockLease)) {
                queue.nack(m);
                return;
            }
            try {
                decider.decide(List.of(q), q.slot(), h);
            } finally {
                slotLock.unlock(key);
            }
            queue.ack(m);
            processed.incrementAndGet();
        }
    }

    /** 접수 창 마감 이벤트(SLOT_CLOSE). 처리 중 건수에 넣어 정책 전환이 이 배정을 기다리게 한다. */
    public void onSlotClose(String slotKey) {
        inFlight.incrementAndGet();
        try {
            closeTime.onSlotClose(slotKey);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public int inFlightCount() {
        return inFlight.get();
    }

    public boolean isPaused() {
        return paused;
    }

    public long processedCount() {
        return processed.get();
    }

    @Override
    public synchronized void close() {
        running = false;
        for (Thread t : threads) t.interrupt();
        for (Thread t : threads) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }
}
