package com.studyroom.control.performance.api;

import com.studyroom.domain.ReservationRequest;

import java.util.List;

/**
 * 접수 큐 (Resource Arbitration).
 * 그룹 ID = 슬롯 키라서 같은 슬롯의 요청은 도착순으로, 한 번에 한 워커에게만 전달된다.
 * 중복 제거 ID = 멱등키라서 같은 요청이 5분 안에 다시 와도 한 번만 전달된다.
 */
public interface IAdmissionQueue {
    /** @throws QueueUnavailableException 큐에 넣을 수 없을 때 (HTTP 503) */
    void enqueue(ReservationRequest request);

    /** 한 그룹에서 최대 max건을 꺼낸다. 없으면 잠깐 기다렸다가 빈 목록을 돌려준다. */
    List<QueueMessage> poll(int max);

    void ack(QueueMessage message);

    /** 처리 실패. 메시지는 다시 전달되고, 최대 수신 횟수를 넘으면 DLQ로 간다. */
    void nack(QueueMessage message);

    /** 대기 중이거나 처리 중인 메시지 수. */
    int depth();
}
