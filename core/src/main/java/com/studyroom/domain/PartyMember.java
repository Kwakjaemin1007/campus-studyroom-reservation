package com.studyroom.domain;

/**
 * 함께 예약하는 사람 한 명(학번)과 그 사람이 거는 크레딧.
 * 크레딧 베팅이 아니면 bid는 0이다. 예약의 우선순위(입찰액)는 모든 구성원 bid의 합이다.
 */
public record PartyMember(String userId, int bid) {
    public PartyMember {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("member userId is required");
        if (bid < 0) throw new IllegalArgumentException("member bid must be >= 0");
    }
}
