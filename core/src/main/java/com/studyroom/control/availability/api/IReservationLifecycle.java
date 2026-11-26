package com.studyroom.control.availability.api;

import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;

import java.util.List;
import java.util.Optional;

/**
 * 예약 상태를 바꾸는 유일한 창구.
 * 모든 전이는 조건부 갱신(updateStatusIf)이라 메시지가 늦게 오거나 두 번 와도 결과가 같다.
 */
public interface IReservationLifecycle {
    enum ConfirmResult { CONFIRMED, SLOT_TAKEN, DUPLICATE }

    enum HoldResult { HELD, REJECTED, DUPLICATE }

    /**
     * 상태 전이 결과. TOO_EARLY·TOO_LATE·WRONG_ROOM은 체크인만 돌려준다(확정 예약인데 시각·호실이 안 맞음).
     */
    enum TransitionResult { APPLIED, NO_OP, NOT_FOUND, TOO_EARLY, TOO_LATE, WRONG_ROOM }

    /** 마감형 정책의 접수: REQUESTED로 저장한다. 입찰액이 있으면 크레딧을 보류한다. */
    HoldResult hold(ReservationRequest request, int policyVersion, String policyName);

    /** 확정 트랜잭션(예산 50ms). 슬롯이 이미 찼으면 거절로 기록하고 SLOT_TAKEN을 돌려준다. */
    ConfirmResult confirm(ReservationRequest request, AllocationDecision decision, int policyVersion, String policyName);

    void reject(ReservationRequest request, String reason, int policyVersion, String policyName);

    /**
     * 입실. 확정(CONFIRMED) 예약만, 시작 10분 전부터 유예가 끝나기 전까지만 된다.
     * roomId가 있으면(호실 입구 기기) 예약한 호실과 같아야 한다. null이면 호실은 보지 않는다.
     * 입실하면 노쇼 타이머를 지우고 이용 종료 타이머(슬롯 종료 시각)를 건다.
     */
    TransitionResult checkIn(String reservationId, String roomId, java.time.Instant at);

    /** 호실을 모르는 기기용. 호실 검사 없이 시각만 본다. */
    default TransitionResult checkIn(String reservationId, java.time.Instant at) {
        return checkIn(reservationId, null, at);
    }

    TransitionResult cancel(String reservationId, String reason);

    /** 학생이 아닌 쪽(관리자·규칙)이 취소할 때. message는 구성원 전원에게 가는 알림 문구 */
    TransitionResult cancel(String reservationId, String reason, String message);

    /** 유예 만료. 아직 CONFIRMED일 때만 노쇼로 해제한다. */
    TransitionResult expireIfNotCheckedIn(String reservationId);

    /** 이용 종료. 입실(CHECKED_IN)한 예약만 COMPLETED로 바꾼다. 종료 메시지가 두 번 와도 한 번만 바뀐다. */
    TransitionResult complete(String reservationId);

    Optional<Reservation> find(String reservationId);

    /** 슬롯에 보류(REQUESTED) 중인 요청을 도착순으로. */
    List<ReservationRequest> heldRequests(SlotId slot);
}
