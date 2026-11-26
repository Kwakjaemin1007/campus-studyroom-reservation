package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import com.studyroom.domain.SlotId;
import com.studyroom.support.CampusTime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 등록된 정책들과 "현재 정책"을 관리한다 (Registry).
 * 배정은 읽기 락(lease), 전환과 시험 기간 등록은 쓰기 락을 잡는다.
 * 자리마다 정책을 고른다: 시험 기간 날짜면 그 기간의 정책, 아니면 평소 정책.
 */
public final class PolicyRegistry implements IPolicyProvider {
    private final Map<String, IAllocationPolicy> policies = new LinkedHashMap<>();
    private final IActivePolicyStore store;
    private final ReadWriteLock lock;
    private final IExamPeriodStore exams;
    /** 이미 시작한 자리에 쓰는 정책. 시험 기간·평소 정책과 관계없이 (11/26 결정: 선착순) */
    static final String LATE_SEAT_POLICY = FcfsPolicy.NAME;

    public PolicyRegistry(Iterable<IAllocationPolicy> policies, String initial,
                          IActivePolicyStore store, ReadWriteLock lock) {
        this(policies, initial, store, lock, null);
    }

    /** @param exams 시험 기간 달력. null이면 모든 날짜가 평소 정책 */
    public PolicyRegistry(Iterable<IAllocationPolicy> policies, String initial,
                          IActivePolicyStore store, ReadWriteLock lock, IExamPeriodStore exams) {
        this.exams = exams;
        for (IAllocationPolicy p : policies) this.policies.put(p.name(), p);
        if (!this.policies.containsKey(initial)) throw new UnknownPolicyException(initial);
        this.store = store;
        this.lock = lock;
        if (store.get() == null) store.set(new ActivePolicy(initial, 1));
    }

    public Set<String> names() {
        return Set.copyOf(policies.keySet());
    }

    public boolean contains(String name) {
        return policies.containsKey(name);
    }

    @Override
    public IAllocationPolicy byName(String name) {
        IAllocationPolicy p = policies.get(name);
        if (p == null) throw new UnknownPolicyException(name);
        return p;
    }

    @Override
    public PolicyHandle current() {
        return resolve(store.get());
    }

    @Override
    public PolicyHandle current(SlotId slot) {
        return resolve(store.get(), slot, null);
    }

    @Override
    public PolicyHandle current(SlotId slot, Instant requestedAt) {
        return resolve(store.get(), slot, requestedAt);
    }

    @Override
    public PolicyLease lease() {
        return lease(null);
    }

    @Override
    public PolicyLease lease(SlotId slot) {
        return lease(slot, null);
    }

    @Override
    public PolicyLease lease(SlotId slot, Instant requestedAt) {
        Lock read = lock.readLock();
        read.lock();
        final PolicyHandle handle;
        try {
            handle = resolve(store.get(), slot, requestedAt);
        } catch (RuntimeException e) {
            read.unlock();
            throw e;
        }
        return new PolicyLease() {
            private boolean closed;

            @Override
            public PolicyHandle handle() {
                return handle;
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    read.unlock();
                }
            }
        };
    }

    /** 전환기가 잡는 쓰기 락. */
    Lock writeLock() {
        return lock.writeLock();
    }

    /** 현재 정책을 바꾸고 버전을 1 올린다. 쓰기 락을 잡은 상태에서만 부른다. */
    PolicyHandle swap(String target) {
        IAllocationPolicy next = policies.get(target);
        if (next == null) throw new UnknownPolicyException(target);
        ActivePolicy updated = new ActivePolicy(target, store.get().version() + 1);
        store.set(updated);
        return new PolicyHandle(next, updated.version());
    }

    /** 시험 기간 등록·취소를 반영한다. 쓰기 락을 잡은 상태에서만 부른다. 정책은 그대로, 버전만 1 올린다. */
    int bumpVersion() {
        ActivePolicy now = store.get();
        ActivePolicy updated = new ActivePolicy(now.name(), now.version() + 1);
        store.set(updated);
        return updated.version();
    }

    private PolicyHandle resolve(ActivePolicy active) {
        return resolve(active, null, null);
    }

    /**
     * 이미 시작한 자리(노쇼로 풀린 자리 등)를 신청하면 시기와 관계없이 선착순(11/26 결정). 남은 시간이 짧아 입찰을 모을 수 없다.
     * 그 밖에는 시험 기간 날짜면 그 기간 정책, 아니면 평소 정책.
     * requestedAt이 null이면 시각을 보지 않는다(마감 일괄 배정은 시작 전에 모인 입찰만 다룬다).
     */
    private PolicyHandle resolve(ActivePolicy active, SlotId slot, Instant requestedAt) {
        String name = active.name();
        boolean started = slot != null && requestedAt != null && !requestedAt.isBefore(slot.startsAt());
        if (started && policies.containsKey(LATE_SEAT_POLICY)) {
            name = LATE_SEAT_POLICY;
        } else if (slot != null && exams != null) {
            Optional<ExamPeriod> exam = exams.on(CampusTime.dayOf(slot.startsAt()));
            if (exam.isPresent()) name = exam.get().policy();
        }
        IAllocationPolicy p = policies.get(name);
        if (p == null) throw new UnknownPolicyException(name);
        return new PolicyHandle(p, active.version());
    }
}
