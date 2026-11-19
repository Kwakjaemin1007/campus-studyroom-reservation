package com.studyroom.app.aws;

import com.studyroom.control.modifiable.api.CreditHold;
import com.studyroom.control.modifiable.api.ICreditRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 크레딧 저장소의 RDS 구현. 규칙은 CreditLedger에 있고, 여기서는 원자적인 읽기·쓰기만 한다.
 * 보류와 환불은 각각 한 트랜잭션, 잔액 차감과 기간 초기화는 조건부 UPDATE로 동시 요청을 막는다.
 */
public final class JdbcCreditRepository implements ICreditRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcCreditRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void resetIfOtherPeriod(String userId, String periodId, int grant) {
        List<String> periods = jdbc.queryForList("SELECT period_id FROM credit_account WHERE user_id = ?", String.class, userId);
        if (periods.isEmpty()) {
            try {
                jdbc.update("INSERT INTO credit_account (user_id, balance, period_id) VALUES (?, ?, ?)", userId, grant, periodId);
            } catch (DuplicateKeyException ignored) {
                // 다른 요청이 먼저 만들었다.
            }
            return;
        }
        if (!periods.get(0).equals(periodId)) {
            // 조건부 UPDATE: 동시에 여러 요청이 와도 초기화는 한 번만 일어난다.
            jdbc.update("UPDATE credit_account SET balance = ?, period_id = ? WHERE user_id = ? AND period_id <> ?",
                    grant, periodId, userId, periodId);
        }
    }

    @Override
    public int balance(String userId) {
        List<Integer> b = jdbc.queryForList("SELECT balance FROM credit_account WHERE user_id = ?", Integer.class, userId);
        return b.isEmpty() ? 0 : b.get(0);
    }

    @Override
    public boolean holdIfAffordable(CreditHold hold) {
        Boolean ok = tx.execute(status -> {
            Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM credit_hold WHERE reservation_id = ?",
                    Integer.class, hold.reservationId());
            if (held != null && held > 0) return true;
            int n = jdbc.update("UPDATE credit_account SET balance = balance - ? WHERE user_id = ? AND balance >= ?",
                    hold.amount(), hold.userId(), hold.amount());
            if (n == 0) return false;
            jdbc.update("INSERT INTO credit_hold (reservation_id, user_id, amount, period_id) VALUES (?,?,?,?)",
                    hold.reservationId(), hold.userId(), hold.amount(), hold.periodId());
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void release(String reservationId, ToIntFunction<CreditHold> refundOf) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, amount, period_id FROM credit_hold WHERE reservation_id = ?", reservationId);
            if (rows.isEmpty()) return;
            if (jdbc.update("DELETE FROM credit_hold WHERE reservation_id = ?", reservationId) == 0) return;
            Map<String, Object> r = rows.get(0);
            CreditHold h = new CreditHold(reservationId, (String) r.get("user_id"),
                    ((Number) r.get("amount")).intValue(), (String) r.get("period_id"));
            int back = refundOf.applyAsInt(h);
            if (back > 0) jdbc.update("UPDATE credit_account SET balance = balance + ? WHERE user_id = ?", back, h.userId());
        });
    }

    @Override
    public int heldSum(String userId, String periodId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM credit_hold WHERE user_id = ? AND period_id = ?",
                Integer.class, userId, periodId);
        return n == null ? 0 : n;
    }

    @Override
    public int holdCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM credit_hold", Integer.class);
        return n == null ? 0 : n;
    }
}
