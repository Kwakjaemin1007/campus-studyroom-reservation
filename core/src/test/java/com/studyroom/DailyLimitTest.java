package com.studyroom;

import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.PriorityPolicy;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 하루 한도: 선착순은 한 사람이 하루 2시간, 크레딧 베팅은 접수 기간 안에 하루 1시간.
 * 슬롯이 1시간이라 "시간 수"와 "슬롯 수"가 같다. 하루는 한국 시각 기준이다.
 */
public class DailyLimitTest {
    private static StudyRoomSystem system(MutableClock clock, String policy) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withInitialPolicy(policy).withRates(100, 5000)).start();
    }

    private static String reasonOf(StudyRoomSystem sys, String id) {
        return sys.repository.findById(id).orElseThrow().reason();
    }

    // ---- 선착순: 하루 2시간 ----

    @Test
    public void fcfsAllowsTwoHoursPerDayAndRejectsTheThird() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            String a = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            String b = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 0);
            String c = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R3", 11), "k3", 0);

            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, a));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, b));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, c));
            assertEquals("DAILY_LIMIT", reasonOf(sys, c));
        }
    }

    @Test
    public void fcfsLimitIsPerDayAndPerUser() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 0);
            String nextDay = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9 + 24), "k3", 0);
            String otherUser = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R3", 11), "k4", 0);

            assertEquals("다음 날은 새 한도", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, nextDay));
            assertEquals("다른 사람은 영향 없음", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, otherUser));
        }
    }

    @Test
    public void dayIsCountedInKoreanTime() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            // 2026-12-02 14:00Z = 한국 23:00, 15:00Z = 한국 다음 날 00:00. 같은 UTC 날짜지만 한국 시각으로는 다른 날이다.
            SlotId lateNight1 = new SlotId("R1", TestSupport.T0.plusSeconds(3600L * (24 + 13)));
            SlotId lateNight2 = new SlotId("R2", TestSupport.T0.plusSeconds(3600L * (24 + 14)));
            SlotId pastMidnight = new SlotId("R3", TestSupport.T0.plusSeconds(3600L * (24 + 15)));
            SlotId pastMidnight2 = new SlotId("R4", TestSupport.T0.plusSeconds(3600L * (24 + 16)));
            SlotId pastMidnight3 = new SlotId("R5", TestSupport.T0.plusSeconds(3600L * (24 + 17)));
            TestSupport.reserveAndWait(sys, "u1", lateNight1, "k1", 0);
            TestSupport.reserveAndWait(sys, "u1", lateNight2, "k2", 0);
            String a = TestSupport.reserveAndWait(sys, "u1", pastMidnight, "k3", 0);
            String b = TestSupport.reserveAndWait(sys, "u1", pastMidnight2, "k4", 0);
            String c = TestSupport.reserveAndWait(sys, "u1", pastMidnight3, "k5", 0);

            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, a));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, b));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, c));
        }
    }

    @Test
    public void cancelingGivesTheHourBackButNoShowDoesNot() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            String a = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 0);
            sys.lifecycle.cancel(a, "USER_CANCELED");
            String again = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R3", 11), "k3", 0);
            assertEquals("직접 취소하면 한도가 돌아온다", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, again));

            // 노쇼로 해제된 예약은 계속 센다.
            String noShow = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R4", 9), "k4", 0);
            TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R5", 10), "k5", 0);
            sys.lifecycle.cancel(noShow, "NO_SHOW");
            String blocked = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R6", 11), "k6", 0);
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, blocked));
        }
    }

    @Test
    public void simultaneousRequestsCannotSlipPastTheLimit() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            // 한 사람이 서로 다른 방 8곳에 동시에 신청. 워커 16개가 병렬로 처리해도 확정은 2건뿐이어야 한다.
            for (int i = 0; i < 8; i++) {
                sys.studentApi.reserve("user:u1", TestSupport.slot("RM" + i, 9), "k" + i, 0);
            }
            sys.awaitQuiescent(5000);
            long confirmed = 0;
            for (int i = 0; i < 8; i++) {
                String id = com.studyroom.support.Ids.requestId("u1", "k" + i);
                if (TestSupport.statusOf(sys, id) == ReservationStatus.CONFIRMED) confirmed++;
            }
            assertEquals(2, confirmed);
        }
    }

    // ---- 크레딧 베팅: 하루 1시간 ----

    @Test
    public void creditBettingAllowsOnlyOneBidSlotPerDayAndDoesNotHoldCreditsForTheExtra() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            String first = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 30);
            String second = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 20);

            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, first));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, second));
            assertEquals("DAILY_LIMIT", reasonOf(sys, second));
            assertEquals("한도에 걸린 입찰은 크레딧을 묶지 않는다", 70, sys.credits.balance("u1"));
            assertEquals(1, sys.credits.openHoldCount());

            String nextDay = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9 + 24), "k3", 10);
            assertEquals("다음 날은 새 한도", ReservationStatus.REQUESTED, TestSupport.statusOf(sys, nextDay));
        }
    }

    @Test
    public void losingABidFreesTheHourButWinningKeepsIt() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            SlotId contested = TestSupport.slot("R1", 9);
            String low = TestSupport.reserveAndWait(sys, "low", contested, "kl", 10);
            String high = TestSupport.reserveAndWait(sys, "high", contested, "kh", 50);
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, low));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, high));

            // 낙찰에 실패한 사람은 그날 다른 시간에 다시 입찰할 수 있다.
            String retry = TestSupport.reserveAndWait(sys, "low", TestSupport.slot("R2", 11), "kl2", 10);
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, retry));
            // 낙찰된 사람은 그날 한도를 이미 썼다.
            String extra = TestSupport.reserveAndWait(sys, "high", TestSupport.slot("R3", 12), "kh2", 10);
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, extra));
            assertEquals("DAILY_LIMIT", reasonOf(sys, extra));
        }
    }

    // ---- 우선순위 정책과 조회 API ----

    @Test
    public void priorityPolicyHasNoDailyLimit() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), PriorityPolicy.NAME)) {
            for (int i = 0; i < 4; i++) {
                String id = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R" + i, 9), "k" + i, 0);
                assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, id));
            }
        }
    }

    @Test
    public void policyEndpointExposesTheLimit() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, FcfsPolicy.NAME)) {
            QueryApi.PolicyView fcfs = sys.queryApi.policy("user:u1").body();
            assertEquals(Integer.valueOf(2), fcfs.maxSlotsPerDay());
        }
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            assertEquals(Integer.valueOf(1), sys.queryApi.policy("user:u1").body().maxSlotsPerDay());
        }
        try (StudyRoomSystem sys = system(clock, PriorityPolicy.NAME)) {
            assertNull(sys.queryApi.policy("user:u1").body().maxSlotsPerDay());
        }
    }
}
