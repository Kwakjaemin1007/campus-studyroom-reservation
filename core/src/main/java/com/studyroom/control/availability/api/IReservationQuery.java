package com.studyroom.control.availability.api;

import com.studyroom.domain.Reservation;
import com.studyroom.domain.SlotId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 예약 조회 창구. 상태를 바꾸지 않는다(상태 변경은 IReservationLifecycle만 한다).
 * 화면이 타임테이블과 내 예약을 그릴 때 쓴다.
 */
public interface IReservationQuery {
    Optional<Reservation> find(String reservationId);

    /** 시작 시각이 [from, to) 안에 있고 지금 차 있는(CONFIRMED, CHECKED_IN) 슬롯. */
    List<SlotId> occupiedSlots(Instant from, Instant to);
}
