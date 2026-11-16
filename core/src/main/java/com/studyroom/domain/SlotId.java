package com.studyroom.domain;

import java.time.Instant;
import java.util.Objects;

/** 예약 단위: 방 하나의 한 시간대. key()는 큐 그룹 ID·락 키·유니크 제약 키로 쓴다. */
public record SlotId(String roomId, Instant startsAt) {
    public SlotId {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(startsAt, "startsAt");
        if (roomId.isBlank() || roomId.contains("@")) throw new IllegalArgumentException("invalid roomId");
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
