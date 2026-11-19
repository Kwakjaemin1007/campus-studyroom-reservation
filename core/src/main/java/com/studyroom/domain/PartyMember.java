package com.studyroom.domain;

/**
 * 함께 예약하는 사람 한 명(학번)과 그 사람의 몫.
 *
 * @param bid      이 사람이 거는 크레딧. 회의 결정(11/19)으로 구성원 전원이 같은 금액(1인당 입찰액)을 건다. 베팅이 아니면 0
 * @param priority 학사 SSO가 알려 준 이 사람의 우선순위. 팀 우선순위는 구성원 우선순위의 합이다
 */
public record PartyMember(String userId, int bid, int priority) {
    public PartyMember {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("member userId is required");
        if (bid < 0) throw new IllegalArgumentException("member bid must be >= 0");
    }

    public PartyMember(String userId, int bid) {
        this(userId, bid, 0);
    }
}
