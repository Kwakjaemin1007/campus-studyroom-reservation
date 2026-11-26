package com.studyroom.domain;

import java.time.Instant;
import java.util.Objects;

/** 예약 단위: 방 하나의 한 시간대. key()는 큐 그룹 ID·락 키·유니크 제약 키로 쓴다. */
public record SlotId(String roomId, Instant startsAt) {
    /** 슬롯 하나의 길이. 시간표가 1시간 단위다. */
    public static final java.time.Duration LENGTH = java.time.Duration.ofHours(1);

    public SlotId {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(startsAt, "startsAt");
        if (roomId.isBlank() || roomId.contains("@")) throw new IllegalArgumentException("invalid roomId");
    }

    /** 이용이 끝나는 시각(시작 + 1시간). 이용 종료 메시지가 이 시각에 온다. */
    /**
     * 입실 마감(= 노쇼 판정 시각). 보통은 시작 + 유예. 이미 시작한 자리를 늦게 잡은 경우(노쇼로 풀린 자리 등)는
     * 신청 시각 + 유예로 미뤄 준다. 어느 쪽이든 슬롯 끝을 넘지 않는다.
     */
    public Instant checkInDeadline(Instant requestedAt, java.time.Duration grace) {
        Instant base = requestedAt != null && requestedAt.isAfter(startsAt) ? requestedAt : startsAt;
        Instant deadline = base.plus(grace);
        return deadline.isAfter(endsAt()) ? endsAt() : deadline;
    }

    public Instant endsAt() {
        return startsAt.plus(LENGTH);
    }

    public String key() {
        return roomId + "@" + startsAt;
    }

    public static SlotId parse(String key) {
        int at = key.indexOf('@');
        if (at < 0) throw new IllegalArgumentException("invalid slot key: " + key);
        return new SlotId(key.substring(0, at), Instant.parse(key.substring(at + 1)));
    }
}
