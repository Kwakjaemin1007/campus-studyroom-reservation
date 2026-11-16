package com.studyroom.app.aws;

import com.studyroom.control.performance.api.CacheUnavailableException;
import com.studyroom.control.performance.api.ISlotStatus;
import com.studyroom.domain.SlotId;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;

/** 슬롯 마감 캐시의 Redis 구현. 실패하면 CacheUnavailableException을 던지고 degraded로 표시한다. */
public final class RedisSlotStatusCache implements ISlotStatus {
    private final RedissonClient redisson;
    private volatile boolean degraded;

    public RedisSlotStatusCache(RedissonClient redisson) {
        this.redisson = redisson;
    }

    private RSet<String> set() {
        return redisson.getSet("studyroom:taken-slots", StringCodec.INSTANCE);
    }

    @Override
    public boolean isTaken(SlotId slot) {
        try {
            return set().contains(slot.key());
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void markTaken(SlotId slot) {
        try {
            set().add(slot.key());
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void release(SlotId slot) {
        try {
            set().remove(slot.key());
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    private CacheUnavailableException unavailable(RedisException e) {
        degraded = true;
        return new CacheUnavailableException(e.getMessage());
    }

    /** 장애가 있었다면 DB의 점유 슬롯으로 캐시를 다시 만든다. @return 다시 만들었으면 true */
    public boolean rebuildIfDegraded(Collection<SlotId> occupied) {
        if (!degraded) return false;
        try {
            RSet<String> s = set();
            s.clear();
            for (SlotId slot : occupied) s.add(slot.key());
            degraded = false;
            return true;
        } catch (RedisException e) {
            return false;
        }
    }
}
