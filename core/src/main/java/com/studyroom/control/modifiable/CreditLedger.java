package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 메모리 장부. 처음 보는 사용자에게는 initialGrant(학기당 지급량, 팀 미정이라 설정값)를 준다.
 * 운영에서는 같은 인터페이스의 DB 구현으로 바꾼다.
 */
public final class CreditLedger implements ICreditLedger {
    private record Hold(String userId, int amount) {
    }

    private final int initialGrant;
    private final Map<String, Integer> balances = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();

    public CreditLedger(int initialGrant) {
        this.initialGrant = initialGrant;
    }

    @Override
    public synchronized boolean reserve(String userId, String reservationId, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be >= 1");
        if (holds.containsKey(reservationId)) return true;
        int available = balances.computeIfAbsent(userId, u -> initialGrant);
        if (available < amount) return false;
        balances.put(userId, available - amount);
        holds.put(reservationId, new Hold(userId, amount));
        return true;
    }

    @Override
    public synchronized void settle(String reservationId, int charge) {
        Hold h = holds.remove(reservationId);
        if (h == null) return;
        int back = h.amount() - Math.min(Math.max(charge, 0), h.amount());
        balances.merge(h.userId(), back, Integer::sum);
    }

    @Override
    public synchronized void refund(String reservationId) {
        Hold h = holds.remove(reservationId);
        if (h == null) return;
        balances.merge(h.userId(), h.amount(), Integer::sum);
    }

    @Override
    public synchronized int balance(String userId) {
        return balances.computeIfAbsent(userId, u -> initialGrant);
    }

    @Override
    public synchronized int openHoldCount() {
        return holds.size();
    }
}
