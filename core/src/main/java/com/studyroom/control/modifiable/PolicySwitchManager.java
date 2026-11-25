package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.control.performance.api.IAllocationGate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;

/**
 * 정책 전환을 지휘하는 중재자 (Feature Toggle + 유휴 전환).
 *
 * 1 요청 → 2 CAS(ACTIVE→SWITCH_REQUESTED) → 2.5 보류 요청 0건 대기 → 3 진행 중 배정 0건 대기 → 4 DRAINING + 임대
 * → 5 pause() → 6 쓰기 락 → 7 교체·버전+1 → 8 resume()·ACTIVE
 *
 * 2.5단계(전환 예약): 마감형 정책의 접수 창이 열려 보류 요청이 있으면 거절하지 않고 받아 둔다.
 * SWITCH_REQUESTED 동안 접수는 새 마감형 신청을 받지 않으므로(ReservationIntake) 보류는 마감이 지나면 0이 된다.
 * collectWait 안에 0이 되지 않으면 FAILED로 끝내고 정책은 그대로 둔다.
 *
 * 3단계에서 idleWait 안에 0건이 되지 않으면 5단계(pause)로 넘어가서 비운다.
 * 어느 경우든 안전은 pause + 쓰기 락이 보장한다.
 */
public final class PolicySwitchManager implements IPolicySwitch, AutoCloseable {
    private final PolicyRegistry registry;
    private final IAllocationGate gate;
    private final ISwitchStateStore state;
    private final BooleanSupplier collectionOpen;
    private final Clock clock;
    private final Duration idleWait;
    private final Duration lease;
    private final Duration collectWait;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "policy-switch");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param collectionOpen 접수 창(베팅·우선순위)이 열려 있어 보류 중인 요청이 있으면 true. 0이 될 때까지 교체를 미룬다
     * @param collectWait    보류가 0이 되기를 기다리는 최대 시간(접수 창 길이보다 길게)
     */
    public PolicySwitchManager(PolicyRegistry registry, IAllocationGate gate, ISwitchStateStore state,
                               BooleanSupplier collectionOpen, Clock clock, Duration idleWait, Duration lease,
                               Duration collectWait) {
        this.collectWait = collectWait;
        this.registry = registry;
        this.gate = gate;
        this.state = state;
        this.collectionOpen = collectionOpen;
        this.clock = clock;
        this.idleWait = idleWait;
        this.lease = lease;
    }

    @Override
    public String requestSwitch(String target) {
        if (!registry.contains(target)) throw new UnknownPolicyException(target);
        recoverIfLeaseExpired();
        PolicyHandle before = registry.current();
        if (before.name().equals(target)) throw new SwitchConflictException("이미 " + target + " 정책이다");
        // 이 서버가 죽어도 SWITCH_REQUESTED에 갇히지 않게 임대를 건다(마감 대기 + 교체 시간).
        // CAS보다 먼저 건다. 뒤에 걸면 그 사이 다른 서버의 복구가 지난 전환의 만료 시각을 보고 되돌릴 수 있다.
        // CAS에 지면 진행 중인 전환의 임대를 늘리기만 하므로 안전하다.
        state.setLeaseExpiry(clock.instant().plus(collectWait).plus(lease));
        // 2단계: CAS. 동시에 요청한 관리자 중 한 명만 통과한다.
        if (!state.compareAndSet(SwitchPhase.ACTIVE, SwitchPhase.SWITCH_REQUESTED)) {
            throw new SwitchConflictException("다른 전환이 진행 중이다");
        }
        String id = "SW-" + UUID.randomUUID();
        String note = collectionOpen.getAsBoolean() ? "보류 요청의 마감을 기다린 뒤 전환한다" : null;
        state.saveStatus(new SwitchStatus(id, target, SwitchStatus.State.REQUESTED,
                before.version(), before.version(), note, clock.instant(), null));
        executor.submit(() -> run(id, target));
        return id;
    }

    @Override
    public Optional<SwitchStatus> status(String switchId) {
        return state.findStatus(switchId);
    }

    @Override
    public SwitchPhase phase() {
        return state.get();
    }

    /**
     * 전환 도중 프로세스가 죽어 DRAINING에 갇힌 경우의 복구.
     * 임대가 지났으면 소비를 재개하고 ACTIVE로 되돌린다. 정책은 바뀌지 않은 것으로 본다.
     * 마감 대기(SWITCH_REQUESTED) 중에 죽은 경우도 같다. 그대로 두면 마감형 신청을 계속 못 받는다.
     */
    public boolean recoverIfLeaseExpired() {
        Instant expiry = state.leaseExpiry();
        if (expiry == null || !clock.instant().isAfter(expiry)) return false;
        SwitchPhase p = state.get();
        if (p == SwitchPhase.DRAINING) {
            gate.resume();
            return state.compareAndSet(SwitchPhase.DRAINING, SwitchPhase.ACTIVE);
        }
        if (p == SwitchPhase.SWITCH_REQUESTED) {
            return state.compareAndSet(SwitchPhase.SWITCH_REQUESTED, SwitchPhase.ACTIVE);
        }
        return false;
    }

    private void run(String id, String target) {
        SwitchStatus st = state.findStatus(id).orElseThrow();
        boolean paused = false;
        try {
            if (!waitCollectionClosed(collectWait)) {                         // 2.5단계
                throw new IllegalStateException("기다리는 동안 보류 요청의 마감이 끝나지 않았다");
            }
            // idleWait 안에 비지 않아도 진행한다. 남은 배정은 6단계 쓰기 락이 끝날 때까지 기다린다.
            waitIdle(idleWait);                                              // 3단계
            state.setLeaseExpiry(clock.instant().plus(lease));               // 4단계
            state.compareAndSet(SwitchPhase.SWITCH_REQUESTED, SwitchPhase.DRAINING);
            state.saveStatus(st = st.with(SwitchStatus.State.DRAINING, st.fromVersion(), null, null));
            gate.pause();                                                    // 5단계
            paused = true;
            Lock write = registry.writeLock();
            if (!write.tryLock(lease.toMillis(), TimeUnit.MILLISECONDS)) {   // 6단계
                throw new IllegalStateException("임대 시간 안에 쓰기 락을 잡지 못했다");
            }
            try {
                if (collectionOpen.getAsBoolean()) {
                    throw new IllegalStateException("전환 직전에 보류 요청이 생겨 취소했다");
                }
                PolicyHandle after = registry.swap(target);                  // 7단계
                state.saveStatus(st.with(SwitchStatus.State.COMPLETED, after.version(), null, clock.instant()));
            } finally {
                write.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.saveStatus(st.with(SwitchStatus.State.FAILED, st.fromVersion(), "interrupted", clock.instant()));
        } catch (RuntimeException e) {
            state.saveStatus(st.with(SwitchStatus.State.FAILED, st.fromVersion(), e.getMessage(), clock.instant()));
        } finally {
            if (paused) gate.resume();                                       // 8단계
            if (!state.compareAndSet(SwitchPhase.DRAINING, SwitchPhase.ACTIVE)) {
                state.compareAndSet(SwitchPhase.SWITCH_REQUESTED, SwitchPhase.ACTIVE);
            }
        }
    }

    private boolean waitCollectionClosed(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (collectionOpen.getAsBoolean()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(50);
        }
        return true;
    }

    private boolean waitIdle(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (gate.inFlightCount() > 0) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(1);
        }
        return true;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
