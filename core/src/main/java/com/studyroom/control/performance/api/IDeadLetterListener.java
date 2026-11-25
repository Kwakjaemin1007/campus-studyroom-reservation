package com.studyroom.control.performance.api;

import com.studyroom.domain.ReservationRequest;

/**
 * 접수 큐가 끝내 처리하지 못한 신청(최대 수신 횟수 초과)을 DLQ로 옮겼다는 사건 (Dead Letter Channel).
 * 큐(Device)는 이 사건만 위로 올리고, 어떻게 정리할지는 Control이 정한다.
 * 수신자가 예외를 던지면 신청은 DLQ에 남아 나중에 다시 전달된다. 그래서 수신자는 멱등해야 한다.
 */
public interface IDeadLetterListener {
    void onDeadLetter(ReservationRequest request);
}
