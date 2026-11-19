package com.studyroom.control.modifiable.api;

/** 학사 시스템(재학생 조회)에 닿지 못했다. 창구는 503으로 바꾼다. */
public class StudentDirectoryUnavailableException extends RuntimeException {
    public StudentDirectoryUnavailableException(String message) {
        super(message);
    }
}
