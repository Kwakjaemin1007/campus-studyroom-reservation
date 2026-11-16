package com.studyroom.control.availability.api;

/** 슬롯 유니크 제약 위반. "이미 마감"으로 처리한다. */
public class SlotAlreadyTakenException extends RuntimeException {
    public SlotAlreadyTakenException(String slotKey) {
        super("slot already taken: " + slotKey);
    }
}
