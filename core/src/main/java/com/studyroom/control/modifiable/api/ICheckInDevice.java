package com.studyroom.control.modifiable.api;

import java.time.Instant;

/** 현장 QR 기기 연동 포트. 기기가 태그를 읽으면 이 이벤트가 아래(Device)에서 위(Control)로 올라온다. */
public interface ICheckInDevice {
    void onTag(String reservationId, Instant at);
}
