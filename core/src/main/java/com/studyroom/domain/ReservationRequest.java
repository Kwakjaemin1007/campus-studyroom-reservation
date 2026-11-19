package com.studyroom.domain;

import java.time.Instant;
import java.util.List;

/**
 * 접수된 예약 요청. 큐를 타고 워커와 정책까지 그대로 전달된다.
 * id는 (userId, 멱등키)에서 결정적으로 만들어지므로 같은 요청은 항상 같은 id를 갖는다.
 *
 * @param sequence  접수 순번(도착 순서). 선착순과 동점 처리의 기준
 * @param bidAmount 크레딧 베팅 입찰액. 함께 신청한 사람들 bid의 합이다. 베팅이 아니면 0
 * @param priority  우선순위 정책에서 쓰는 값. 클수록 우선
 * @param party     함께 이용하는 사람 전부(신청자가 첫 번째). 같은 시간 중복과 하루 한도를 사람마다 검사한다
 */
public record ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                                 Instant requestedAt, long sequence, int bidAmount, int priority,
                                 List<PartyMember> party) {
    public ReservationRequest {
        party = party == null || party.isEmpty() ? List.of(new PartyMember(userId, bidAmount)) : List.copyOf(party);
    }

    /** 혼자 신청한 요청(구성원 = 신청자). 테스트·내부 도구용 */
    public ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                              Instant requestedAt, long sequence, int bidAmount, int priority) {
        this(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, null);
    }

    public List<String> memberIds() {
        return party.stream().map(PartyMember::userId).toList();
    }
}
