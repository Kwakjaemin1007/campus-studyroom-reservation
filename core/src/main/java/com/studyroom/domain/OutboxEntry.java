package com.studyroom.domain;

import java.time.Instant;

/**
 * Outbox 한 줄. 예약 상태 변경과 같은 트랜잭션에 저장되고, 커밋 뒤에 따로 발행된다.
 * 발행 전에 프로세스가 죽어도 OutboxRelay가 다시 발행한다.
 */
public record OutboxEntry(String id, Kind kind, String reservationId, String userId,
                          Instant fireAt, NotificationEvent.Type eventType, String message) {
    public enum Kind { SCHEDULE_NOSHOW_TIMER, NOTIFY }

    public static OutboxEntry noShowTimer(String reservationId, Instant fireAt) {
        return new OutboxEntry(reservationId + ":timer", Kind.SCHEDULE_NOSHOW_TIMER, reservationId, null, fireAt, null, null);
    }

    public static OutboxEntry notify(String reservationId, String userId, NotificationEvent.Type type, String message) {
        return new OutboxEntry(reservationId + ":notify:" + type, Kind.NOTIFY, reservationId, userId, null, type, message);
    }
}
