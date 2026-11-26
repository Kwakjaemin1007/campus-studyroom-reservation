package com.studyroom.control.availability;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.availability.api.ISessionEndListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 이용 종료 처리(멱등 수신자). 입실한 예약을 슬롯 종료 시각에 COMPLETED로 바꾼다.
 * complete는 "CHECKED_IN일 때만" 바꾸는 조건부 UPDATE라서, 메시지가 두 번 와도 한 번만 바뀐다.
 */
public final class SessionEndHandler implements ISessionEndListener {
    private final IReservationLifecycle lifecycle;
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    public SessionEndHandler(IReservationLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void onSlotEnded(String reservationId) {
        if (lifecycle.complete(reservationId) == IReservationLifecycle.TransitionResult.APPLIED) {
            completed.incrementAndGet();
        } else {
            ignored.incrementAndGet();
        }
    }

    public long completedCount() {
        return completed.get();
    }

    public long ignoredCount() {
        return ignored.get();
    }
}
