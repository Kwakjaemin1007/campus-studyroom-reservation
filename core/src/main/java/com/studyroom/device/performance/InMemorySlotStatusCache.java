package com.studyroom.device.performance;

import com.studyroom.control.performance.api.CacheUnavailableException;
import com.studyroom.control.performance.api.ISlotStatus;
import com.studyroom.domain.SlotId;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Redis 슬롯 캐시를 흉내 낸 메모리 구현. setAvailable(false)로 Redis 장애를 주입한다. */
public final class InMemorySlotStatusCache implements ISlotStatus {
    private final Set<String> taken = ConcurrentHashMap.newKeySet();
    private volatile boolean up = true;

    @Override
    public boolean isTaken(SlotId slot) {
        check();
        return taken.contains(slot.key());
    }

    @Override
    public void markTaken(SlotId slot) {
        check();
        taken.add(slot.key());
    }

    @Override
    public void release(SlotId slot) {
        check();
        taken.remove(slot.key());
    }

    private void check() {
        if (!up) throw new CacheUnavailableException("cache is down");
    }

    public void setAvailable(boolean available) {
        this.up = available;
    }

    /** 복구 직후: 내용이 사라진 캐시를 DB의 점유 슬롯으로 다시 채운다. */
    public void rebuild(Collection<SlotId> occupied) {
        taken.clear();
        for (SlotId s : occupied) taken.add(s.key());
    }

    public int size() {
        return taken.size();
    }
}
