package com.studyroom.device.modifiable;

import com.studyroom.control.availability.api.ICheckIn;
import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.modifiable.api.ICheckInDevice;

import java.time.Instant;

/**
 * 현장 QR 기기 Adapter. 기기 고유의 신호 형식을 여기서 흡수하고,
 * 위(Control)로는 표준 이벤트 ICheckIn.checkIn(reservationId, at)만 올린다.
 */
public final class QrCheckInAdapter implements ICheckInDevice {
    private static final String PREFIX = "RSV:";
    private final ICheckIn checkIn;
    private volatile IReservationLifecycle.TransitionResult lastResult;

    public QrCheckInAdapter(ICheckIn checkIn) {
        this.checkIn = checkIn;
    }

    @Override
    public void onTag(String reservationId, Instant at) {
        lastResult = checkIn.checkIn(reservationId, at);
    }

    /** 기기가 보내는 원시 QR 문자열("RSV:{예약 id}")을 받는 진입점. 형식이 바뀌면 여기만 고친다. */
    public IReservationLifecycle.TransitionResult onRawScan(String qrPayload, Instant at) {
        return onRawScan(qrPayload, null, at);
    }

    /** roomId: 기기가 설치된 호실(모르면 null). 예약한 호실과 다르면 WRONG_ROOM. */
    public IReservationLifecycle.TransitionResult onRawScan(String qrPayload, String roomId, Instant at) {
        if (qrPayload == null || !qrPayload.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unknown QR payload");
        }
        String room = roomId == null || roomId.isBlank() ? null : roomId.trim();
        IReservationLifecycle.TransitionResult result = checkIn.checkIn(qrPayload.substring(PREFIX.length()), room, at);
        lastResult = result;
        return result;
    }

    /** 기기 화면에 성공/실패를 띄우기 위한 마지막 처리 결과. */
    public IReservationLifecycle.TransitionResult lastResult() {
        return lastResult;
    }
}
