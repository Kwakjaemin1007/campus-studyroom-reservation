package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.CreditHold;
import com.studyroom.control.modifiable.api.ICreditRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** 크레딧 저장소의 메모리 구현. 메서드 하나가 원자 단위(synchronized)다. */
public final class InMemoryCreditRepository implements ICreditRepository {
    private static final class Account {
        String periodId;
        int balance;

        Account(String periodId, int balance) {
            this.periodId = periodId;
            this.balance = balance;
        }
    }

    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, CreditHold> holds = new HashMap<>();

    @Override
    public synchronized void resetIfOtherPeriod(String userId, String periodId, int grant) {
        Account a = accounts.get(userId);
        if (a == null) {
            accounts.put(userId, new Account(periodId, grant));
        } else if (!a.periodId.equals(periodId)) {
            a.periodId = periodId;
            a.balance = grant;
        }
    }

    @Override
    public synchronized int balance(String userId) {
        Account a = accounts.get(userId);
        return a == null ? 0 : a.balance;
    }

    @Override
    public synchronized boolean holdIfAffordable(CreditHold hold) {
        if (holds.containsKey(hold.reservationId())) return true;
        Account a = accounts.get(hold.userId());
        if (a == null || a.balance < hold.amount()) return false;
        a.balance -= hold.amount();
        holds.put(hold.reservationId(), hold);
        return true;
    }

    @Override
    public synchronized void release(String reservationId, ToIntFunction<CreditHold> refundOf) {
        CreditHold h = holds.remove(reservationId);
        if (h == null) return;
        int back = refundOf.applyAsInt(h);
        Account a = accounts.get(h.userId());
        if (back > 0 && a != null) a.balance += back;
    }

    @Override
    public synchronized int heldSum(String userId, String periodId) {
        return holds.values().stream()
                .filter(h -> h.userId().equals(userId) && h.periodId().equals(periodId))
                .mapToInt(CreditHold::amount).sum();
    }

    @Override
    public synchronized int holdCount() {
        return holds.size();
    }
}
