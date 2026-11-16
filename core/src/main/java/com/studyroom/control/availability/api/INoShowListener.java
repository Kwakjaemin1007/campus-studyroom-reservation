package com.studyroom.control.availability.api;

/** 유예 만료 이벤트 수신자. 예외를 던지면 메시지가 다시 전달된다. */
public interface INoShowListener {
    void onGraceExpired(String reservationId);
}
