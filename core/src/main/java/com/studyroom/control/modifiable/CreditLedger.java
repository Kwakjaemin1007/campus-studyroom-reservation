package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;

import java.time.Clock;
import java.time.Instant;

/**
 * 크레딧 규칙. 저장은 ICreditRepository(Device)에 맡기고 여기에는 규칙만 둔다.
 *  - 계정은 처음 쓸 때, 그리고 갱신 기간(학기)이 바뀐 뒤 처음 쓸 때 지급량(initialGrant)으로 맞춘다. 남은 잔액은 버린다
 *  - 입찰 시 보류, 낙찰 시 낙찰가만 차감하고 나머지 환불, 탈락 시 전액 환불
 *  - 지난 기간에 입찰한 보류분은 정산·환불해도 새 잔액에 더하지 않는다
 * 메모리 저장소든 RDS 저장소든 이 규칙은 한 벌이다.
 */
public final class CreditLedger implements ICreditLedger {
    private final ICreditRepository repository;
    private final int initialGrant;
    private final Clock clock;
    private final ICreditPeriod period;

    /** 갱신 없이 쓰는 장부(기간이 영원히 하나). 테스트·단순 조립용 */
    public CreditLedger(ICreditRepository repository, int initialGrant) {
        this(repository, initialGrant, Clock.systemUTC(), ICreditPeriod.FOREVER);
    }

    public CreditLedger(ICreditRepository repository, int initialGrant, Clock clock, ICreditPeriod period) {
        this.repository = repository;
        this.initialGrant = initialGrant;
        this.clock = clock;
        this.period = period;
    }

    private String currentPeriod() {
        return period.idOf(clock.instant());
    }

    private String ensureAccount(String userId) {
        String now = currentPeriod();
        repository.resetIfOtherPeriod(userId, now, initialGrant);
        return now;
    }

    @Override
    public boolean reserve(String userId, String reservationId, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be >= 1");
        String now = ensureAccount(userId);
        return repository.holdIfAffordable(new CreditHold(reservationId, userId, amount, now));
    }

    @Override
    public void settle(String reservationId, int charge) {
        release(reservationId, charge);
    }

    @Override
    public void refund(String reservationId) {
        release(reservationId, 0);
    }

    /** 보류를 풀고, 보류액에서 charge를 뺀 나머지를 돌려준다. */
    private void release(String reservationId, int charge) {
        String now = currentPeriod();
        repository.release(reservationId, h -> {
            if (!h.periodId().equals(now)) return 0; // 지난 기간의 크레딧은 이미 초기화되었다
            return h.amount() - Math.min(Math.max(charge, 0), h.amount());
        });
    }

    @Override
    public int balance(String userId) {
        ensureAccount(userId);
        return repository.balance(userId);
    }

    @Override
    public int held(String userId) {
        return repository.heldSum(userId, currentPeriod());
    }

    @Override
    public int openHoldCount() {
        return repository.holdCount();
    }

    @Override
    public Instant renewsAt() {
        return period.endOf(clock.instant());
    }
}
