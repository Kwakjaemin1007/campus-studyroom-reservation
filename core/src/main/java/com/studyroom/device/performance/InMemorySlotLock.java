package com.studyroom.device.performance;

import com.studyroom.control.performance.api.ISlotLock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Redisson RLock을 흉내 낸 메모리 락. lease가 지나면 다른 쪽이 잡을 수 있다. */
public final class InMemorySlotLock implements ISlotLock {
    private final ConcurrentHashMap<String, Long> expiryNanos = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String slotKey, Duration lease) {
        long now = System.nanoTime();
        boolean[] acquired = {false};
        expiryNanos.compute(slotKey, (k, expiry) -> {
            if (expiry == null || expiry - now <= 0) {
                acquired[0] = true;
                return now + lease.toNanos();
            }
            return expiry;
        });
        return acquired[0];
    }

    @Override
    public void unlock(String slotKey) {
        expiryNanos.remove(slotKey);
    }
}
