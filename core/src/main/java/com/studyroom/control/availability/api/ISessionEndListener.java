package com.studyroom.control.availability.api;

/**
 * 이용 종료 이벤트. 슬롯 종료 시각에 지연 메시지(SLOT_END)로 아래에서 위로 올라온다.
 * 같은 메시지가 두 번 오거나 DLQ에서 다시 와도 결과가 같아야 한다(멱등 수신자).
 */
public interface ISessionEndListener {
    void onSlotEnded(String reservationId);
}
