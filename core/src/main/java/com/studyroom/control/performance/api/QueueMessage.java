package com.studyroom.control.performance.api;

import com.studyroom.domain.ReservationRequest;

/** 큐에서 꺼낸 메시지 한 건. receiptHandle로 ack/nack한다. */
public record QueueMessage(String receiptHandle, String groupId, ReservationRequest request, int receiveCount) {
}
