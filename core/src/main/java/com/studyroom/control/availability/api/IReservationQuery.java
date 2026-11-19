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

    /**
     * 그 사람이 한국 시각 기준 그날 이미 쓴(또는 쓰기로 한) 슬롯 수. 하루 한도 판단에 쓴다.
     * 센다: 접수 중(REQUESTED), 확정, 입실, 이용 완료, 노쇼로 해제된 예약.
     * 세지 않는다: 거절, 직접 취소(자리를 돌려준 것).
     */
    int slotsUsedOnDay(String userId, java.time.LocalDate day);

    /**
     * 그 사람(신청자 또는 동행)이 그 시각에 이미 들어가 있는 진행 중 예약(접수 중·확정·입실).
     * 같은 시간 중복 예약을 막는 데 쓴다. 슬롯은 정시 1시간 단위라 시작 시각이 같으면 겹친다.
     */
    Optional<Reservation> activeAt(String userId, Instant startsAt);

    /**
     * 그 사람(신청자 또는 동행)이 구성원으로 들어간 예약 중 시작 시각이 [from, to)이고 노쇼로 해제된 것의 수.
     * 마감 배정에서 팀 패널티(동점 처리)를 계산하는 데 쓴다. 노쇼 예약의 구성원 전원이 1회씩 받는다.
     */
    int noShowsBetween(String userId, Instant from, Instant to);
}
