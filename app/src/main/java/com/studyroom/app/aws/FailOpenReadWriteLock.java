package com.studyroom.app.aws;

import org.redisson.client.RedisException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 정책 읽기-쓰기 락(Redisson RReadWriteLock)을 감싼다.
 *  - 읽기 락(배정): Redis가 죽으면 락 없이 진행한다. 배정이 Redis 장애로 멈추지 않게 하기 위해서다
 *  - 쓰기 락(전환): 그대로 실패한다. Redis가 죽은 동안에는 정책을 바꾸지 않는다
 */
public final class FailOpenReadWriteLock implements ReadWriteLock {
    private final ReadWriteLock delegate;
    private final Lock read;

    public FailOpenReadWriteLock(ReadWriteLock delegate) {
        this.delegate = delegate;
        this.read = new FailOpenLock(delegate.readLock());
    }

    @Override
    public Lock readLock() {
        return read;
    }

    @Override
    public Lock writeLock() {
        return delegate.writeLock();
    }

    private static final class FailOpenLock implements Lock {
        private final Lock target;
        private final ThreadLocal<Integer> held = ThreadLocal.withInitial(() -> 0);

        FailOpenLock(Lock target) {
            this.target = target;
        }

        @Override
        public void lock() {
            try {
                target.lock();
                held.set(held.get() + 1);
            } catch (RedisException e) {
                // 락 없이 진행한다.
            }
        }

        @Override
        public void unlock() {
            if (held.get() == 0) return;
            held.set(held.get() - 1);
            try {
                target.unlock();
            } catch (RedisException | IllegalMonitorStateException ignored) {
                // 임대 시간이 지나 이미 풀렸다.
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            target.lockInterruptibly();
            held.set(held.get() + 1);
        }

        @Override
        public boolean tryLock() {
            boolean ok = target.tryLock();
            if (ok) held.set(held.get() + 1);
            return ok;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            boolean ok = target.tryLock(time, unit);
            if (ok) held.set(held.get() + 1);
            return ok;
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
