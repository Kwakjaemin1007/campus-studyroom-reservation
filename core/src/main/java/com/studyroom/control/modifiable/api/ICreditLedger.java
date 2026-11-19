package com.studyroom.control.modifiable.api;

import com.studyroom.domain.PartyMember;

import java.util.List;

/**
 * 크레딧 장부. 입찰 시 보류(reserve), 낙찰 시 차감(settle), 탈락 시 환불(refund).
 * 세 메서드 모두 같은 reservationId로 여러 번 불려도 결과가 같다(멱등).
 * 함께 신청한 예약은 구성원마다 자기 잔액에서 자기 몫(bid)을 보류한다. 예약의 입찰액은 몫의 합이다.
 */
public interface ICreditLedger {
    /**
     * 구성원 전원의 몫을 보류한다. 한 명이라도 잔액이 모자라면 아무도 보류하지 않는다(전부 아니면 없음).
     * bid가 0인 구성원은 건너뛴다.
     *
     * @return 누군가의 잔액이 부족하면 false
     */
    boolean reserve(String reservationId, List<PartyMember> shares);

    /** 혼자 거는 입찰 */
    default boolean reserve(String userId, String reservationId, int amount) {
        return reserve(reservationId, List.of(new PartyMember(userId, amount)));
    }

    /**
     * 보류분에서 charge만큼 차감하고 남는 금액은 돌려준다.
     * 여러 명이 건 경우 charge를 인원수로 똑같이 나눈다(1인당 올림, 자기 몫을 넘지 않음).
     */
    void settle(String reservationId, int charge);

    void refund(String reservationId);

    int balance(String userId);

    /** 이 사용자의 입찰로 묶여 있는(아직 정산·환불되지 않은) 크레딧 합계. */
    int held(String userId);

    /** 아직 정산·환불되지 않은 보류 건수. */
    int openHoldCount();

    /** 다음 갱신 시각. 이때 모든 사용자의 잔액이 지급량으로 초기화된다. */
    java.time.Instant renewsAt();
}
