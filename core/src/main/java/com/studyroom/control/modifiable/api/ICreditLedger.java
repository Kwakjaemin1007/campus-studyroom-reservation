package com.studyroom.control.modifiable.api;

/**
 * 크레딧 장부. 입찰 시 보류(reserve), 낙찰 시 차감(settle), 탈락 시 환불(refund).
 * 세 메서드 모두 같은 reservationId로 여러 번 불려도 결과가 같다(멱등).
 */
public interface ICreditLedger {
    /** @return 잔액이 부족하면 false */
    boolean reserve(String userId, String reservationId, int amount);

    /** 보류분에서 charge만큼 차감하고 남는 금액은 돌려준다. */
    void settle(String reservationId, int charge);

    void refund(String reservationId);

    int balance(String userId);

    /** 아직 정산·환불되지 않은 보류 건수. */
    int openHoldCount();
}
