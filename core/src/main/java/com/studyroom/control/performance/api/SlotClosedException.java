package com.studyroom.control.performance.api;

/** 이미 마감된 슬롯이다 (HTTP 409, 재시도 금지). */
public class SlotClosedException extends RuntimeException {
    public SlotClosedException(String slotKey) {
        super("slot already taken: " + slotKey);
    }
}
