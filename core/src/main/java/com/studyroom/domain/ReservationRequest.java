package com.studyroom.domain;

import java.time.Instant;

/**
 * 접수된 예약 요청. 큐를 타고 워커와 정책까지 그대로 전달된다.
 * id는 (userId, 멱등키)에서 결정적으로 만들어지므로 같은 요청은 항상 같은 id를 갖는다.
 *
 * @param sequence  접수 순번(도착 순서). 선착순과 동점 처리의 기준
 * @param bidAmount 크레딧 베팅 입찰액. 베팅이 아니면 0
 * @param priority  우선순위 정책에서 쓰는 값. 클수록 우선
 */
public record ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                                 Instant requestedAt, long sequence, int bidAmount, int priority) {
}
