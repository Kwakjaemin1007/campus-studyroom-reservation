package com.studyroom.boundary;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 유입 제한 (Manage event rate). 사용자별 한도와 피크보다 높은 전역 한도를 함께 둔다.
 * 전역 한도 하나만 두면 정상 요청이 429로 떨어져 "유실 0"을 어기기 때문이다.
 * 운영에서는 API Gateway의 스로틀링이 이 일을 맡는다.
 */
public final class RateLimiter {
    private final Clock clock;
    private final int perUserPerSecond;
    private final int globalPerSecond;
    private final ConcurrentHashMap<String, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final AtomicInteger global = new AtomicInteger();
    private volatile long windowSecond;

    public RateLimiter(Clock clock, int perUserPerSecond, int globalPerSecond) {
        this.clock = clock;
        this.perUserPerSecond = perUserPerSecond;
        this.globalPerSecond = globalPerSecond;
    }

    public boolean tryAcquire(String userId) {
        roll();
        if (global.incrementAndGet() > globalPerSecond) return false;
        return perUser.computeIfAbsent(userId, u -> new AtomicInteger()).incrementAndGet() <= perUserPerSecond;
    }

    private void roll() {
        long sec = clock.instant().getEpochSecond();
        if (sec == windowSecond) return;
        synchronized (this) {
            if (sec != windowSecond) {
                perUser.clear();
                global.set(0);
                windowSecond = sec;
            }
        }
    }
}
