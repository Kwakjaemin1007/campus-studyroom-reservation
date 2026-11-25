package com.studyroom.domain;

import java.time.Instant;
import java.util.List;

/**
 * 접수된 예약 요청. 큐를 타고 워커와 정책까지 그대로 전달된다.
 * id는 (userId, 멱등키)에서 결정적으로 만들어지므로 같은 요청은 항상 같은 id를 갖는다.
 *
 * @param sequence  접수 순번(도착 순서). 선착순과 동점 처리의 기준
 * @param bidAmount   팀 입찰액 = 1인당 입찰액 × 인원(구성원 bid의 합). 베팅이 아니면 0
 * @param priority    팀 우선순위 = 구성원 우선순위의 합. 클수록 우선
 * @param party       함께 이용하는 사람 전부(신청자가 첫 번째). 같은 시간 중복과 하루 한도를 사람마다 검사한다
 * @param teamPenalty 구성원의 이번 학기 노쇼 횟수 합. 마감 배정 직전에 채운다(저장하지 않음). 합계가 같을 때 적은 쪽이 우선
 * @param policyVersion 접수할 때의 정책 버전. 0이면 검사하지 않음
 * @param policyName    접수할 때 이 자리에 적용된 정책. 배정할 때 다르면 바뀐 규칙으로 판정하지 않고 거절한다.
 *                      이름으로 비교하므로 다른 날짜의 정책이 바뀌어 버전만 오른 경우에는 그대로 판정한다. null이면 버전으로 비교
 */
public record ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                                 Instant requestedAt, long sequence, int bidAmount, int priority,
                                 List<PartyMember> party, int teamPenalty, int policyVersion,
                                 String policyName) {
    public ReservationRequest {
        party = party == null || party.isEmpty() ? List.of(new PartyMember(userId, bidAmount, priority)) : List.copyOf(party);
    }

    public ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                              Instant requestedAt, long sequence, int bidAmount, int priority,
                              List<PartyMember> party) {
        this(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, party, 0, 0, null);
    }

    /** 혼자 신청한 요청(구성원 = 신청자). 테스트·내부 도구용 */
    public ReservationRequest(String id, String idempotencyKey, String userId, SlotId slot,
                              Instant requestedAt, long sequence, int bidAmount, int priority) {
        this(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, null, 0, 0, null);
    }

    public ReservationRequest withTeamPenalty(int penalty) {
        return new ReservationRequest(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, party, penalty, policyVersion, policyName);
    }

    public ReservationRequest withPolicyVersion(int version) {
        return new ReservationRequest(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, party, teamPenalty,
                version, policyName);
    }

    /** 접수 시점의 정책(이름·버전)을 찍는다 */
    public ReservationRequest withPolicy(String name, int version) {
        return new ReservationRequest(id, idempotencyKey, userId, slot, requestedAt, sequence, bidAmount, priority, party, teamPenalty,
                version, name);
    }

    /** 지금 이 자리에 적용되는 정책과 접수 때 정책이 다른가. 다르면 판정하지 않고 돌려보낸다 */
    public boolean policyChangedSince(String currentName, int currentVersion) {
        if (policyName != null) return !policyName.equals(currentName);
        return policyVersion != 0 && policyVersion != currentVersion;
    }

    public int partySize() {
        return party.size();
    }

    public List<String> memberIds() {
        return party.stream().map(PartyMember::userId).toList();
    }
}
