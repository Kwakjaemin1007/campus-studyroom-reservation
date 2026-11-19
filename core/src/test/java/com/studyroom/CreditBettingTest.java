package com.studyroom;

import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.PriorityPolicy;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 크레딧 베팅: 봉인 입찰, 최고 입찰 낙찰, 동점은 먼저 입찰한 사람. 보류 → 차감/환불. */
public class CreditBettingTest {
    private static StudyRoomSystem system(MutableClock clock, CreditBettingPolicy.Pricing pricing) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)
                .withInitialPolicy(CreditBettingPolicy.NAME).withPricing(pricing)).start();
    }

    @Test
    public void highestBidWinsAndTieGoesToEarlierBidder() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.Pricing.FIRST_PRICE)) {
            SlotId slot = TestSupport.slot("R1", 9);
            String a = TestSupport.reserveAndWait(sys, "a", slot, "ka", 30);
            String b = TestSupport.reserveAndWait(sys, "b", slot, "kb", 50);
            String c = TestSupport.reserveAndWait(sys, "c", slot, "kc", 50);

            // 입찰하는 순간 크레딧이 보류된다.
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, b));
            assertEquals(70, sys.credits.balance("a"));
            assertEquals(50, sys.credits.balance("b"));
            assertEquals(3, sys.credits.openHoldCount());

            // 접수 창이 닫히면 슬롯별로 한 번에 판정한다.
            clock.advance(sys.config.collectWindow());
            assertEquals(1, sys.scheduler.tick());

            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, a));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, b));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, c));
            Reservation winner = sys.repository.findById(b).orElseThrow();
            assertEquals(50, winner.chargedAmount());
            assertEquals("탈락자는 전액 환불", 100, sys.credits.balance("a"));
            assertEquals("낙찰자는 차감", 50, sys.credits.balance("b"));
            assertEquals(100, sys.credits.balance("c"));
            assertEquals(0, sys.credits.openHoldCount());
        }
    }

    @Test
    public void secondPriceChargesTheRunnerUpBid() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.Pricing.SECOND_PRICE)) {
            SlotId slot = TestSupport.slot("R1", 9);
            TestSupport.reserveAndWait(sys, "a", slot, "ka", 30);
            String b = TestSupport.reserveAndWait(sys, "b", slot, "kb", 50);
            TestSupport.reserveAndWait(sys, "c", slot, "kc", 40);
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(40, sys.repository.findById(b).orElseThrow().chargedAmount());
            assertEquals(60, sys.credits.balance("b"));
        }
    }

    @Test
    public void bidAboveBalanceIsRejectedAtOnce() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.Pricing.FIRST_PRICE)) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "ka", 150);
            Reservation r = sys.repository.findById(id).orElseThrow();
            assertEquals(ReservationStatus.REJECTED, r.status());
            assertEquals("INSUFFICIENT_CREDIT", r.reason());
            assertEquals(100, sys.credits.balance("a"));
        }
    }

    @Test
    public void cancelingAnOpenBidRefundsTheHold() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.Pricing.FIRST_PRICE)) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "ka", 40);
            assertEquals(60, sys.credits.balance("a"));
            assertEquals(200, sys.studentApi.cancel("user:a", id).status());
            assertEquals(100, sys.credits.balance("a"));
            assertEquals(ReservationStatus.CANCELED, TestSupport.statusOf(sys, id));
        }
    }

    @Test
    public void priorityPolicyPicksTheHighestPriorityAtClose() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock,
                SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(PriorityPolicy.NAME)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            assertEquals(202, sys.studentApi.reserve("user:low:1", slot, "k1", 0).status());
            assertEquals(202, sys.studentApi.reserve("user:high:5", slot, "k2", 0).status());
            sys.awaitQuiescent(5000);
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(1, sys.repository.count(ReservationStatus.CONFIRMED));
            Reservation winner = sys.repository.all().stream()
                    .filter(r -> r.status() == ReservationStatus.CONFIRMED).findFirst().orElseThrow();
            assertEquals("high", winner.userId());
        }
    }
}
