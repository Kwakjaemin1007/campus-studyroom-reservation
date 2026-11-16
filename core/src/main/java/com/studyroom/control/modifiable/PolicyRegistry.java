package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 등록된 정책들과 "현재 정책"을 관리한다 (Registry).
 * 배정은 읽기 락(lease), 전환은 쓰기 락을 잡는다.
 */
public final class PolicyRegistry implements IPolicyProvider {
    private final Map<String, IAllocationPolicy> policies = new LinkedHashMap<>();
    private final IActivePolicyStore store;
    private final ReadWriteLock lock;

    public PolicyRegistry(Iterable<IAllocationPolicy> policies, String initial,
                          IActivePolicyStore store, ReadWriteLock lock) {
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
    public PolicyHandle current() {
        return resolve(store.get());
    }

    @Override
    public PolicyLease lease() {
        Lock read = lock.readLock();
        read.lock();
        final PolicyHandle handle;
        try {
            handle = resolve(store.get());
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

    private PolicyHandle resolve(ActivePolicy active) {
        IAllocationPolicy p = policies.get(active.name());
        if (p == null) throw new UnknownPolicyException(active.name());
        return new PolicyHandle(p, active.version());
    }
}
