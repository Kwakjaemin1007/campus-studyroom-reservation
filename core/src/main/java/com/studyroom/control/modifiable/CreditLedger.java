package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 메모리 장부. 계정은 처음 쓸 때 initialGrant(학기당 지급량, 팀 미정이라 설정값)로 만들고,
 * 갱신 기간(학기)이 바뀌면 다음에 건드릴 때 지급량으로 초기화한다(남은 잔액은 버린다).
 * 이전 기간에 입찰한 보류분은 기간이 바뀐 뒤 정산·환불해도 새 잔액에 반영하지 않는다.
 * 운영에서는 같은 인터페이스의 DB 구현으로 바꾼다.
 */
public final class CreditLedger implements ICreditLedger {
    private record Hold(String userId, int amount, String periodId) {
    }

    private static final class Account {
        String periodId;
        int balance;

        Account(String periodId, int balance) {
            this.periodId = periodId;
            this.balance = balance;
        }
    }

    private final int initialGrant;
    private final Clock clock;
    private final ICreditPeriod period;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();

    /** 갱신 없이 쓰는 장부(기간이 영원히 하나). 테스트·단순 조립용 */
    public CreditLedger(int initialGrant) {
        this(initialGrant, Clock.systemUTC(), new ICreditPeriod() {
            @Override
            public String idOf(Instant at) {
                return "forever";
            }

            @Override
            public Instant endOf(Instant at) {
                return Instant.MAX;
            }
        });
    }

    public CreditLedger(int initialGrant, Clock clock, ICreditPeriod period) {
        this.initialGrant = initialGrant;
        this.clock = clock;
        this.period = period;
    }

    private String currentPeriod() {
        return period.idOf(clock.instant());
    }

    /** 현재 기간의 계정. 없거나 지난 기간의 것이면 지급량으로 새로 만든다. */
    private Account account(String userId) {
        String now = currentPeriod();
        Account a = accounts.get(userId);
        if (a == null || !a.periodId.equals(now)) {
            a = new Account(now, initialGrant);
            accounts.put(userId, a);
        }
        return a;
    }

    @Override
    public synchronized boolean reserve(String userId, String reservationId, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be >= 1");
        if (holds.containsKey(reservationId)) return true;
        Account a = account(userId);
        if (a.balance < amount) return false;
        a.balance -= amount;
        holds.put(reservationId, new Hold(userId, amount, a.periodId));
        return true;
    }

    @Override
    public synchronized void settle(String reservationId, int charge) {
        Hold h = holds.remove(reservationId);
        if (h == null) return;
        int back = h.amount() - Math.min(Math.max(charge, 0), h.amount());
        giveBack(h, back);
    }

    @Override
    public synchronized void refund(String reservationId) {
        Hold h = holds.remove(reservationId);
        if (h == null) return;
        giveBack(h, h.amount());
    }

    private void giveBack(Hold h, int amount) {
        if (!h.periodId().equals(currentPeriod())) return; // 지난 기간의 크레딧은 이미 초기화되었다
        account(h.userId()).balance += amount;
    }

    @Override
    public synchronized int balance(String userId) {
        return account(userId).balance;
    }

    @Override
    public synchronized int held(String userId) {
        String now = currentPeriod();
        return holds.values().stream()
                .filter(h -> h.userId().equals(userId) && h.periodId().equals(now))
                .mapToInt(Hold::amount).sum();
    }

    @Override
    public synchronized int openHoldCount() {
        return holds.size();
    }

    @Override
    public Instant renewsAt() {
        return period.endOf(clock.instant());
    }
}
