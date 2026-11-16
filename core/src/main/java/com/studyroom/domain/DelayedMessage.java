package com.studyroom.domain;

/** 지연 메시지 본문. NO_SHOW의 subject는 예약 id, SLOT_CLOSE의 subject는 슬롯 키. */
public record DelayedMessage(Type type, String subject) {
    public enum Type { NO_SHOW, SLOT_CLOSE }
}
