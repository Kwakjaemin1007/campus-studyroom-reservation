package com.studyroom.control.availability.api;

import java.time.Instant;

/** 체크인 이벤트의 표준 형식. QR Adapter가 기기 신호를 이 형식으로 바꿔 넘긴다. */
public interface ICheckIn {
    /** roomId는 태그한 기기가 놓인 호실. 모르면 null(호실 검사 생략). */
    IReservationLifecycle.TransitionResult checkIn(String reservationId, String roomId, Instant at);

    default IReservationLifecycle.TransitionResult checkIn(String reservationId, Instant at) {
        return checkIn(reservationId, null, at);
    }
}
