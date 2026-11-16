package com.studyroom.control.availability.api;

import java.time.Instant;

/** 체크인 이벤트의 표준 형식. QR Adapter가 기기 신호를 이 형식으로 바꿔 넘긴다. */
public interface ICheckIn {
    IReservationLifecycle.TransitionResult checkIn(String reservationId, Instant at);
}
