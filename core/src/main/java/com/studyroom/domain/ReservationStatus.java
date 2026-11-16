package com.studyroom.domain;

/**
 * 예약 상태.
 * REQUESTED → CONFIRMED / REJECTED / CANCELED,
 * CONFIRMED → CHECKED_IN / CANCELED(노쇼 포함),
 * CHECKED_IN → COMPLETED.
 */
public enum ReservationStatus {
    REQUESTED, CONFIRMED, REJECTED, CHECKED_IN, CANCELED, COMPLETED;

    /** 슬롯을 차지하고 있는 상태인가 (유니크 제약 대상). */
    public boolean occupiesSlot() {
        return this == CONFIRMED || this == CHECKED_IN;
    }
}
