package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import java.util.concurrent.atomic.AtomicLong;

/** 유예 만료 이벤트 처리(멱등 수신자). 같은 메시지가 두 번 와도, 체크인 뒤에 와도 결과가 같다. */
public final class NoShowTimeoutHandler implements INoShowListener {
    private final IReservationLifecycle lifecycle;
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    public NoShowTimeoutHandler(IReservationLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void onGraceExpired(String reservationId) {
        if (lifecycle.expireIfNotCheckedIn(reservationId) == IReservationLifecycle.TransitionResult.APPLIED) {
            released.incrementAndGet();
        } else {
            ignored.incrementAndGet();
        }
    }

    public long releasedCount() {
        return released.get();
    }

    public long ignoredCount() {
        return ignored.get();
    }
}
