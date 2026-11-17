package com.studyroom.app.aws;

import com.studyroom.control.modifiable.api.ICreditLedger;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 크레딧 장부의 RDS 구현. 보류·차감·환불을 각각 한 트랜잭션으로 처리한다.
 * 계정과 보류에 갱신 기간(period_id)을 적어 두고, 기간이 바뀐 계정은 처음 건드릴 때 지급량으로 초기화한다.
 * 지난 기간에 입찰한 보류분은 정산·환불해도 새 잔액에 반영하지 않는다.
 */
public final class JdbcCreditLedger implements ICreditLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int initialGrant;
    private final Clock clock;
    private final ICreditPeriod period;

    public JdbcCreditLedger(JdbcTemplate jdbc, TransactionTemplate tx, int initialGrant, Clock clock, ICreditPeriod period) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.initialGrant = initialGrant;
        this.clock = clock;
        this.period = period;
    }

    private String currentPeriod() {
        return period.idOf(clock.instant());
    }

    /** 현재 기간의 계정을 보장한다. 없으면 만들고, 지난 기간의 것이면 지급량으로 초기화한다. */
    private void ensureAccount(String userId) {
        String now = currentPeriod();
        List<String> periods = jdbc.queryForList("SELECT period_id FROM credit_account WHERE user_id = ?", String.class, userId);
        if (periods.isEmpty()) {
            try {
                jdbc.update("INSERT INTO credit_account (user_id, balance, period_id) VALUES (?, ?, ?)", userId, initialGrant, now);
            } catch (DuplicateKeyException ignored) {
                // 다른 요청이 먼저 만들었다.
            }
            return;
        }
        if (!periods.get(0).equals(now)) {
            // 조건부 UPDATE: 동시에 여러 요청이 와도 초기화는 한 번만 일어난다.
            jdbc.update("UPDATE credit_account SET balance = ?, period_id = ? WHERE user_id = ? AND period_id <> ?",
                    initialGrant, now, userId, now);
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
            jdbc.update("INSERT INTO credit_hold (reservation_id, user_id, amount, period_id) VALUES (?,?,?,?)",
                    reservationId, userId, amount, currentPeriod());
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
                    "SELECT user_id, amount, period_id FROM credit_hold WHERE reservation_id = ?", reservationId);
            if (rows.isEmpty()) return;
            if (jdbc.update("DELETE FROM credit_hold WHERE reservation_id = ?", reservationId) == 0) return;
            String userId = (String) rows.get(0).get("user_id");
            int amount = ((Number) rows.get(0).get("amount")).intValue();
            // 지난 기간의 크레딧은 이미 초기화되었으므로 돌려주지 않는다.
            if (!currentPeriod().equals(rows.get(0).get("period_id"))) return;
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
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM credit_hold WHERE user_id = ? AND period_id = ?",
                Integer.class, userId, currentPeriod());
        return n == null ? 0 : n;
    }

    @Override
    public int openHoldCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM credit_hold", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public Instant renewsAt() {
        return period.endOf(clock.instant());
    }
}
