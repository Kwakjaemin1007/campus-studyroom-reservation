package com.studyroom.control.performance;

import com.studyroom.control.performance.api.*;
import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.AllocationTiming;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.PolicyLease;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.DelayedMessage;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.SlotState;
import com.studyroom.support.CampusTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 큐 소비자(경쟁 소비자 N개): 큐 수신 → 슬롯 락 → 정책 판단 → 확정/거절 요청 → 락 해제.
 * 확정 건당 100ms 예산. 정책이 무엇인지는 모르고 IAllocationPolicy만 부른다.
 *
 * 접수 창이 닫힌 뒤 판정하는 정책(우선순위·베팅)이면 요청을 보류(REQUESTED)로 저장해 두고,
 * 창이 닫히는 시각의 지연 메시지를 받아 슬롯별로 한 번에 배정한다(onSlotClose).
 */
public final class AllocationProcessor implements IAllocationGate, AutoCloseable {
    private final IAdmissionQueue queue;
    private final ISlotLock slotLock;
    private final ISlotStatus slotStatus;
    private final IPolicyProvider policies;
    private final IReservationLifecycle lifecycle;
    private final IReservationQuery reservations;
    private final IDelayedMessage delayed;
    private final Clock clock;
    private final int workers;
    private final Duration lockLease;
    private final Duration collectWindow;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong processed = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean paused;
    private volatile boolean running;

    public AllocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, ISlotStatus slotStatus,
                               IPolicyProvider policies, IReservationLifecycle lifecycle,
                               IReservationQuery reservations, IDelayedMessage delayed, Clock clock,
                               int workers, Duration lockLease, Duration collectWindow) {
        this.queue = queue;
        this.slotLock = slotLock;
        this.slotStatus = slotStatus;
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.reservations = reservations;
        this.delayed = delayed;
        this.clock = clock;
        this.workers = workers;
        this.lockLease = lockLease;
        this.collectWindow = collectWindow;
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
        try (PolicyLease lease = policies.lease()) {
            PolicyHandle h = lease.handle();
            ReservationRequest q = m.request();
            // 하루 한도: 같은 사람의 요청이 동시에 다른 워커에서 처리돼도 한도를 넘지 않게 사람·날짜별 락 안에서 센다.
            String quotaKey = quotaKeyIfLimited(q, h);
            if (quotaKey != null && !lockWithWait(quotaKey)) {
                queue.nack(m);
                return;
            }
            try {
                if (quotaKey != null && overDailyLimit(q, h)) {
                    lifecycle.reject(q, DAILY_LIMIT, h.version(), h.name());
                    queue.ack(m);
                    processed.incrementAndGet();
                    return;
                }
                if (h.policy().timing() == AllocationTiming.AT_CLOSE) {
                    collect(q, h);
                    queue.ack(m);
                    return;
                }
                String key = q.slot().key();
                if (!slotLock.tryLock(key, lockLease)) {
                    queue.nack(m);
                    return;
                }
                try {
                    decide(List.of(q), q.slot(), h);
                } finally {
                    slotLock.unlock(key);
                }
                queue.ack(m);
                processed.incrementAndGet();
            } finally {
                if (quotaKey != null) slotLock.unlock(quotaKey);
            }
        } catch (RuntimeException e) {
            queue.nack(m);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 거절 사유: 하루 한도 초과. 화면이 이 값으로 안내 문구를 고른다. */
    public static final String DAILY_LIMIT = "DAILY_LIMIT";

    /**
     * 같은 사람의 요청이 동시에 올 때만 부딪히는 락이다. 잡고 있는 시간이 확정 한 건(수십 ms)이라 잠깐 기다린다.
     * 곧바로 nack하면 수신 횟수가 소진돼 DLQ로 가 버리므로, 정말 못 잡을 때만 nack한다.
     */
    private boolean lockWithWait(String key) {
        long deadline = System.nanoTime() + QUOTA_LOCK_WAIT.toNanos();
        while (true) {
            if (slotLock.tryLock(key, lockLease)) return true;
            if (System.nanoTime() >= deadline) return false;
            LockSupport.parkNanos(2_000_000);
        }
    }

    private static final Duration QUOTA_LOCK_WAIT = Duration.ofSeconds(1);

    private String quotaKeyIfLimited(ReservationRequest q, PolicyHandle h) {
        if (h.policy().maxSlotsPerDay() == IAllocationPolicy.UNLIMITED) return null;
        return "quota:" + q.userId() + "@" + CampusTime.dayOf(q.slot().startsAt());
    }

    /**
     * 이 요청을 받으면 그날 한도를 넘는가. 이미 기록된 요청(큐 재전달)은 자기 자신을 세지 않도록 통과시킨다.
     * 접수 중인 입찰도 센다. 그래서 크레딧 베팅은 하루에 한 슬롯에만 입찰할 수 있다.
     */
    private boolean overDailyLimit(ReservationRequest q, PolicyHandle h) {
        if (lifecycle.find(q.id()).isPresent()) return false;
        int used = reservations.slotsUsedOnDay(q.userId(), CampusTime.dayOf(q.slot().startsAt()));
        return used >= h.policy().maxSlotsPerDay();
    }

    /** 마감형 정책: 요청을 보류로 저장하고, 슬롯의 마감 메시지를 (없으면) 건다. */
    private void collect(ReservationRequest q, PolicyHandle h) {
        lifecycle.hold(q, h.version(), h.name());
        Instant closeAt = clock.instant().plus(collectWindow);
        if (closeAt.isAfter(q.slot().startsAt())) closeAt = q.slot().startsAt();
        // 같은 키로 여러 번 걸어도 처음 것만 남는다(멱등).
        delayed.schedule("slot-close:" + q.slot().key(), closeAt,
                new DelayedMessage(DelayedMessage.Type.SLOT_CLOSE, q.slot().key()));
        processed.incrementAndGet();
    }

    /** 접수 창 마감 이벤트. 그 슬롯에 보류된 요청 전부를 한 번에 판정한다. */
    public void onSlotClose(String slotKey) {
        SlotId slot = SlotId.parse(slotKey);
        inFlight.incrementAndGet();
        try (PolicyLease lease = policies.lease()) {
            if (!slotLock.tryLock(slotKey, lockLease)) throw new IllegalStateException("slot is busy: " + slotKey);
            try {
                List<ReservationRequest> held = lifecycle.heldRequests(slot);
                if (!held.isEmpty()) decide(held, slot, lease.handle());
            } finally {
                slotLock.unlock(slotKey);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void decide(List<ReservationRequest> requests, SlotId slot, PolicyHandle h) {
        Map<String, ReservationRequest> byId = new HashMap<>();
        for (ReservationRequest q : requests) byId.put(q.id(), q);
        List<AllocationDecision> decisions = h.policy().allocate(requests, new SlotState(slot, isTakenOrUnknown(slot)));
        for (AllocationDecision d : decisions) {
            ReservationRequest q = byId.get(d.requestId());
            if (q == null) continue;
            if (d.outcome() == AllocationDecision.Outcome.CONFIRM) {
                IReservationLifecycle.ConfirmResult r = lifecycle.confirm(q, d, h.version(), h.name());
                if (r == IReservationLifecycle.ConfirmResult.CONFIRMED) markTakenQuietly(slot);
                if (r == IReservationLifecycle.ConfirmResult.SLOT_TAKEN) markTakenQuietly(slot);
            } else {
                lifecycle.reject(q, d.reason(), h.version(), h.name());
            }
        }
    }

    /** 캐시가 죽었으면 "비어 있다"고 보고 진행한다. 틀렸다면 DB 유니크 제약이 막는다. */
    private boolean isTakenOrUnknown(SlotId slot) {
        try {
            return slotStatus.isTaken(slot);
        } catch (CacheUnavailableException e) {
            return false;
        }
    }

    private void markTakenQuietly(SlotId slot) {
        try {
            slotStatus.markTaken(slot);
        } catch (CacheUnavailableException ignored) {
            // 캐시는 힌트일 뿐이다.
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
