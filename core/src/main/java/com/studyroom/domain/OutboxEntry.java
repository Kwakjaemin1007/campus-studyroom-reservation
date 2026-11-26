package com.studyroom.domain;

import java.time.Instant;

/**
 * Outbox 한 줄. 예약 상태 변경과 같은 트랜잭션에 저장되고, 커밋 뒤에 따로 발행된다.
 * 발행 전에 프로세스가 죽어도 OutboxRelay가 다시 발행한다.
 */
public record OutboxEntry(String id, Kind kind, String reservationId, String userId,
                          Instant fireAt, NotificationEvent.Type eventType, String message) {
    public enum Kind { SCHEDULE_NOSHOW_TIMER, SCHEDULE_END_TIMER, NOTIFY }

    public static OutboxEntry noShowTimer(String reservationId, Instant fireAt) {
        return new OutboxEntry(reservationId + ":timer", Kind.SCHEDULE_NOSHOW_TIMER, reservationId, null, fireAt, null, null);
    }

    /** 이용 종료 타이머. 입실과 같은 트랜잭션에 저장해, 입실은 됐는데 종료 타이머가 빠지는 일이 없게 한다. */
    public static OutboxEntry endTimer(String reservationId, Instant fireAt) {
        return new OutboxEntry(reservationId + ":end", Kind.SCHEDULE_END_TIMER, reservationId, null, fireAt, null, null);
    }

    /** 알림 한 건. 함께 신청한 예약은 구성원마다 한 건씩 만든다(id에 학번이 들어가 서로 겹치지 않는다). */
    public static OutboxEntry notify(String reservationId, String userId, NotificationEvent.Type type, String message) {
        return new OutboxEntry(reservationId + ":notify:" + type + ":" + userId, Kind.NOTIFY, reservationId, userId, null, type, message);
    }
}
