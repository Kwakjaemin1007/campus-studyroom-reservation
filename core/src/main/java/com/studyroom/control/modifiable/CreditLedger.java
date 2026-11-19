package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;

import com.studyroom.domain.PartyMember;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 크레딧 규칙. 저장은 ICreditRepository(Device)에 맡기고 여기에는 규칙만 둔다.
 *  - 계정은 처음 쓸 때, 그리고 갱신 기간(학기)이 바뀐 뒤 처음 쓸 때 지급량(initialGrant)으로 맞춘다. 남은 잔액은 버린다
 *  - 입찰 시 보류, 낙찰 시 낙찰가만 차감하고 나머지 환불, 탈락 시 전액 환불
 *  - 지난 기간에 입찰한 보류분은 정산·환불해도 새 잔액에 더하지 않는다
 *  - 함께 신청한 예약은 구성원마다 자기 몫을 보류하고, 한 명이라도 모자라면 전원 보류하지 않는다
 *  - 구성원 전원이 같은 1인당 입찰액을 걸고, 낙찰가도 똑같이 나눠 낸다(splitCharge)
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
    public boolean reserve(String reservationId, List<PartyMember> shares) {
        if (shares.stream().noneMatch(m -> m.bid() > 0)) throw new IllegalArgumentException("at least one bid must be >= 1");
        for (PartyMember m : shares) {
            if (m.bid() == 0) continue;
            String now = ensureAccount(m.userId());
            if (!repository.holdIfAffordable(new CreditHold(reservationId, m.userId(), m.bid(), now))) {
                // 한 명이라도 모자라면 이미 묶은 몫을 그대로 돌려준다(전부 아니면 없음).
                repository.release(reservationId, CreditHold::amount);
                return false;
            }
        }
        return true;
    }

    @Override
    public void settle(String reservationId, int charge) {
        release(reservationId, charge);
    }

    @Override
    public void refund(String reservationId) {
        release(reservationId, 0);
    }

    /** 보류를 풀고, 구성원마다 자기 몫에서 자기 부담분을 뺀 나머지를 돌려준다. */
    private void release(String reservationId, int charge) {
        String now = currentPeriod();
        Map<String, Integer> owed = splitCharge(repository.holdsOf(reservationId), Math.max(charge, 0));
        repository.release(reservationId, h -> {
            if (!h.periodId().equals(now)) return 0; // 지난 기간의 크레딧은 이미 초기화되었다
            return h.amount() - owed.getOrDefault(h.userId(), 0);
        });
    }

    /**
     * 낙찰가를 구성원 전원이 똑같이 나눠 낸다(회의 결정 11/19: 전원이 같은 비용).
     * 1인당 부담 = ⌈낙찰가 ÷ 인원⌉. 1등가(FIRST_PRICE)면 낙찰가 = 1인당 입찰액 × 인원이라 각자 자기 몫을 그대로 낸다.
     * 차순위 금액 방식에서 나누어떨어지지 않으면 올림하므로 합계가 낙찰가보다 최대 (인원-1) 크레딧 많을 수 있다.
     * 누구도 자기 몫보다 더 내지 않는다.
     */
    static Map<String, Integer> splitCharge(List<CreditHold> holds, int charge) {
        Map<String, Integer> owed = new LinkedHashMap<>();
        if (holds.isEmpty() || charge <= 0) return owed;
        int perHead = (charge + holds.size() - 1) / holds.size();
        for (CreditHold h : holds) owed.put(h.userId(), Math.min(perHead, h.amount()));
        return owed;
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
