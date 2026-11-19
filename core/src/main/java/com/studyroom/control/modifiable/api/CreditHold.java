package com.studyroom.control.modifiable.api;

/** 입찰로 묶인 크레딧 한 건(예약 × 구성원). periodId는 입찰한 갱신 기간(학기). */
public record CreditHold(String reservationId, String userId, int amount, String periodId) {
}
