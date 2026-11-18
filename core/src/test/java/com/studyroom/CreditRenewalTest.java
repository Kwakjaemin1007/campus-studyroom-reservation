package com.studyroom;

import com.studyroom.control.modifiable.CreditLedger;
import com.studyroom.control.modifiable.SemesterCreditPeriod;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 크레딧은 학기가 바뀔 때만 지급량으로 초기화된다. 학기는 3월 1일, 9월 1일 0시(한국 시각)에 시작한다. */
public class CreditRenewalTest {
    private static final Instant IN_SEMESTER_2 = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    public void periodBoundariesFollowTheKoreanCalendar() {
        SemesterCreditPeriod p = new SemesterCreditPeriod();
        assertEquals("2026-1", p.idOf(Instant.parse("2026-03-01T00:00:00+09:00")));
        assertEquals("2026-1", p.idOf(Instant.parse("2026-08-31T23:59:59+09:00")));
        assertEquals("2026-2", p.idOf(Instant.parse("2026-09-01T00:00:00+09:00")));
        assertEquals("2026-2", p.idOf(Instant.parse("2027-02-28T23:59:59+09:00")));
        assertEquals("2027-1", p.idOf(Instant.parse("2027-03-01T00:00:00+09:00")));
        assertEquals("한국 시각 2월은 전 해 2학기", "2025-2", p.idOf(Instant.parse("2026-02-10T12:00:00+09:00")));
        assertEquals(Instant.parse("2027-03-01T00:00:00+09:00"), p.endOf(IN_SEMESTER_2));
        assertEquals(Instant.parse("2026-09-01T00:00:00+09:00"), p.endOf(Instant.parse("2026-05-01T00:00:00+09:00")));
        assertEquals(Instant.parse("2026-03-01T00:00:00+09:00"), p.endOf(Instant.parse("2026-01-15T00:00:00+09:00")));
    }

    @Test
    public void balanceIsNotRefilledInsideAPeriodButResetWhenItEnds() {
        MutableClock clock = new MutableClock(IN_SEMESTER_2);
        CreditLedger ledger = new CreditLedger(100, clock, new SemesterCreditPeriod());
        assertTrue(ledger.reserve("a", "r1", 70));
        ledger.settle("r1", 70);
        assertEquals(30, ledger.balance("a"));

        clock.advance(java.time.Duration.ofDays(89)); // 2027-02-28 (같은 학기)
        assertEquals("학기 중에는 다시 채워지지 않는다", 30, ledger.balance("a"));

        clock.advance(java.time.Duration.ofDays(2)); // 2027-03-02 (새 학기)
        assertEquals("새 학기에는 지급량으로 초기화", 100, ledger.balance("a"));
        assertEquals(Instant.parse("2027-09-01T00:00:00+09:00"), ledger.renewsAt());
    }

    @Test
    public void unusedBalanceIsDiscardedNotCarriedOver() {
        MutableClock clock = new MutableClock(IN_SEMESTER_2);
        CreditLedger ledger = new CreditLedger(100, clock, new SemesterCreditPeriod());
        assertEquals(100, ledger.balance("a"));
        clock.advance(java.time.Duration.ofDays(120));
        assertEquals("남은 100이 이월되어 200이 되지 않는다", 100, ledger.balance("a"));
    }

    @Test
    public void holdsFromAnEarlierPeriodDoNotRefundIntoTheNewBalance() {
        MutableClock clock = new MutableClock(IN_SEMESTER_2);
        CreditLedger ledger = new CreditLedger(100, clock, new SemesterCreditPeriod());
        assertTrue(ledger.reserve("a", "r1", 40));
        assertEquals(40, ledger.held("a"));

        clock.advance(java.time.Duration.ofDays(120)); // 다음 학기
        assertEquals(100, ledger.balance("a"));
        assertEquals("지난 학기의 보류는 새 학기 화면에 나오지 않는다", 0, ledger.held("a"));
        ledger.refund("r1");
        assertEquals("지난 학기 입찰의 환불이 새 잔액을 늘리지 않는다", 100, ledger.balance("a"));
        assertEquals(0, ledger.openHoldCount());
    }
}
