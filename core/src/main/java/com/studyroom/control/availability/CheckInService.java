package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import java.time.Instant;

/** QR 체크인 이벤트 처리. */
public final class CheckInService implements ICheckIn {
    private final IReservationLifecycle lifecycle;

    public CheckInService(IReservationLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public IReservationLifecycle.TransitionResult checkIn(String reservationId, String roomId, Instant at) {
        return lifecycle.checkIn(reservationId, roomId, at);
    }
}
