package com.studyroom.domain;

import java.time.Instant;

/** 저장소에 기록되는 예약 한 건(불변 스냅샷). 상태 변경은 ReservationLifecycle만 한다. */
public record Reservation(String id, String idempotencyKey, String userId, SlotId slot,
                          ReservationStatus status, String reason,
                          int policyVersion, String policyName,
                          int bidAmount, int chargedAmount, int priority, long sequence,
                          Instant requestedAt, Instant updatedAt) {

    public static Reservation from(ReservationRequest q, ReservationStatus status, String reason,
                                   int policyVersion, String policyName, int charged, Instant now) {
        return new Reservation(q.id(), q.idempotencyKey(), q.userId(), q.slot(), status, reason,
                policyVersion, policyName, q.bidAmount(), charged, q.priority(), q.sequence(),
                q.requestedAt(), now);
    }

    public Reservation with(ReservationStatus newStatus, String newReason, Instant now) {
        return new Reservation(id, idempotencyKey, userId, slot, newStatus, newReason,
                policyVersion, policyName, bidAmount, chargedAmount, priority, sequence, requestedAt, now);
    }

    public Reservation confirmedBy(int version, String name, int charged, Instant now) {
        return new Reservation(id, idempotencyKey, userId, slot, ReservationStatus.CONFIRMED, null,
                version, name, bidAmount, charged, priority, sequence, requestedAt, now);
    }

    public ReservationRequest toRequest() {
        return new ReservationRequest(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority);
    }
}
