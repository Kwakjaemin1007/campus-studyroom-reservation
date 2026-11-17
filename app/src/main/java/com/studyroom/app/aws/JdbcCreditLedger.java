package com.studyroom.app.aws;

import com.studyroom.control.modifiable.api.ICreditLedger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/** 크레딧 장부의 RDS 구현. 보류·차감·환불을 각각 한 트랜잭션으로 처리한다. */
public final class JdbcCreditLedger implements ICreditLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int initialGrant;

    public JdbcCreditLedger(JdbcTemplate jdbc, TransactionTemplate tx, int initialGrant) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.initialGrant = initialGrant;
    }

    private void ensureAccount(String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM credit_account WHERE user_id = ?", Integer.class, userId);
        if (n != null && n > 0) return;
        try {
            jdbc.update("INSERT INTO credit_account (user_id, balance) VALUES (?, ?)", userId, initialGrant);
        } catch (DuplicateKeyException ignored) {
            // 다른 요청이 먼저 만들었다.
        }
    }

    @Override
    public boolean reserve(String userId, String reservationId, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be >= 1");
        ensureAccount(userId);
        Boolean ok = tx.execute(status -> {
            Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM credit_hold WHERE reservation_id = ?", Integer.class, reservationId);
            if (held != null && held > 0) return true;
            // 잔액이 충분할 때만 줄인다(조건부 UPDATE).
            int n = jdbc.update("UPDATE credit_account SET balance = balance - ? WHERE user_id = ? AND balance >= ?",
                    amount, userId, amount);
            if (n == 0) return false;
            jdbc.update("INSERT INTO credit_hold (reservation_id, user_id, amount) VALUES (?,?,?)", reservationId, userId, amount);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void settle(String reservationId, int charge) {
        release(reservationId, charge);
    }

    @Override
    public void refund(String reservationId) {
        release(reservationId, 0);
    }

    /** 보류를 지우고, 보류액에서 charge를 뺀 나머지를 돌려준다. 보류가 없으면 아무 일도 하지 않는다. */
    private void release(String reservationId, int charge) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, amount FROM credit_hold WHERE reservation_id = ?", reservationId);
            if (rows.isEmpty()) return;
            if (jdbc.update("DELETE FROM credit_hold WHERE reservation_id = ?", reservationId) == 0) return;
            String userId = (String) rows.get(0).get("user_id");
            int amount = ((Number) rows.get(0).get("amount")).intValue();
            int back = amount - Math.min(Math.max(charge, 0), amount);
            if (back > 0) jdbc.update("UPDATE credit_account SET balance = balance + ? WHERE user_id = ?", back, userId);
        });
    }

    @Override
    public int balance(String userId) {
        ensureAccount(userId);
        Integer b = jdbc.queryForObject("SELECT balance FROM credit_account WHERE user_id = ?", Integer.class, userId);
        return b == null ? 0 : b;
    }

    @Override
    public int held(String userId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM credit_hold WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    @Override
    public int openHoldCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM credit_hold", Integer.class);
        return n == null ? 0 : n;
    }
}
