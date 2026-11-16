package com.studyroom.control.availability.api;

import com.studyroom.domain.DelayedMessage;

import java.time.Instant;

/**
 * 1회성 지연 메시지. 풀 스캔 배치 대신 "예약마다 알람 하나"를 건다.
 * 같은 key로 다시 등록하면 성공으로 처리하고 처음 것을 유지한다(멱등).
 */
public interface IDelayedMessage {
    void schedule(String key, Instant at, DelayedMessage payload);

    void cancel(String key);
}
