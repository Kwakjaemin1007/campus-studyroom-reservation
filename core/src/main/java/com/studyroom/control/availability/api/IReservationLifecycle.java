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

    enum TransitionResult { APPLIED, NO_OP, NOT_FOUND }

    /** 마감형 정책의 접수: REQUESTED로 저장한다. 입찰액이 있으면 크레딧을 보류한다. */
    HoldResult hold(ReservationRequest request, int policyVersion, String policyName);

    /** 확정 트랜잭션(예산 50ms). 슬롯이 이미 찼으면 거절로 기록하고 SLOT_TAKEN을 돌려준다. */
    ConfirmResult confirm(ReservationRequest request, AllocationDecision decision, int policyVersion, String policyName);

    void reject(ReservationRequest request, String reason, int policyVersion, String policyName);

    TransitionResult checkIn(String reservationId, java.time.Instant at);

    TransitionResult cancel(String reservationId, String reason);

    /** 유예 만료. 아직 CONFIRMED일 때만 노쇼로 해제한다. */
    TransitionResult expireIfNotCheckedIn(String reservationId);

    TransitionResult complete(String reservationId);

    Optional<Reservation> find(String reservationId);

    /** 슬롯에 보류(REQUESTED) 중인 요청을 도착순으로. */
    List<ReservationRequest> heldRequests(SlotId slot);
}
