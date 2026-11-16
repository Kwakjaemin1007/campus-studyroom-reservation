package com.studyroom.control.modifiable.api;

/** 이미 다른 전환이 진행 중이거나, 지금은 전환할 수 없는 상태다 (HTTP 409). */
public class SwitchConflictException extends RuntimeException {
    public SwitchConflictException(String message) {
        super(message);
    }
}
