package com.studyroom.control.modifiable.api;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 크레딧 계정과 보류의 저장소(Device). 규칙은 갖지 않고, 값을 원자적으로 읽고 쓰기만 한다.
 * 얼마를 돌려줄지, 언제 초기화할지는 CreditLedger(Control)가 정해서 넘긴다.
 */
public interface ICreditRepository {
    /** 계정이 없거나 다른 기간의 것이면 잔액을 grant, 기간을 periodId로 맞춘다. 동시에 불려도 한 번만 일어난다. */
    void resetIfOtherPeriod(String userId, String periodId, int grant);

    int balance(String userId);

    /**
     * (reservationId, userId)의 보류가 이미 있으면 아무것도 하지 않고 true(멱등).
     * 잔액이 amount 이상이면 그만큼 줄이고 보류를 기록한 뒤 true, 모자라면 false. 한 번에(원자적으로) 처리한다.
     */
    boolean holdIfAffordable(CreditHold hold);

    /**
     * 이 예약의 보류를 모두 지우고, 보류 한 건마다 refundOf가 돌려준 금액을 그 사용자 잔액에 더한다.
     * 한 번에(원자적으로) 처리한다. 보류가 없으면 아무것도 하지 않는다.
     */
    void release(String reservationId, ToIntFunction<CreditHold> refundOf);

    /** 이 예약에 걸린 보류(구성원마다 한 건) */
    List<CreditHold> holdsOf(String reservationId);

    /** 이 사용자가 periodId 기간에 묶어 둔 크레딧 합계 */
    int heldSum(String userId, String periodId);

    int holdCount();
}
