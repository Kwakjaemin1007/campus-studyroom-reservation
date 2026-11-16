package com.studyroom.app.aws;

import com.studyroom.control.performance.api.ISlotLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 슬롯 락의 Redisson 구현.
 * Redis가 죽으면 락 없이 진행한다(QAS-5). 그때는 큐의 그룹 배타 전달과 DB 유니크 제약이 방어한다.
 */
public final class RedissonSlotLock implements ISlotLock {
    private final RedissonClient redisson;

    public RedissonSlotLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    private RLock lock(String slotKey) {
        return redisson.getLock("studyroom:slot-lock:" + slotKey);
    }

    @Override
    public boolean tryLock(String slotKey, Duration lease) {
        try {
            return lock(slotKey).tryLock(0, lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RedisException e) {
            return true;
        }
    }

    @Override
    public void unlock(String slotKey) {
        try {
            RLock l = lock(slotKey);
            if (l.isHeldByCurrentThread()) l.unlock();
        } catch (RedisException ignored) {
            // lease가 지나면 저절로 풀린다.
        }
    }
}
