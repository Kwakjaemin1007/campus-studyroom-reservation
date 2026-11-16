package com.studyroom.control.availability.api;

/** 예약 id 유니크 제약 위반. 같은 요청이 이미 기록돼 있다. */
public class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException(String id) {
        super("request already recorded: " + id);
    }
}
