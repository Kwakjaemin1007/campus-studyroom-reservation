package com.studyroom;

import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.api.OperatingHours;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 하루 한도: 선착순은 한 사람이 하루 1시간, 크레딧 베팅은 하루 2시간.
 * 슬롯이 1시간이라 "시간 수"와 "슬롯 수"가 같다. 하루는 한국 시각 기준이다.
 */
public class DailyLimitTest {
    private static StudyRoomSystem system(MutableClock clock, String policy) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(policy).withRates(100, 5000)).start();
    }

    private static String reasonOf(StudyRoomSystem sys, String id) {
        return sys.repository.findById(id).orElseThrow().reason();
    }

    // ---- 선착순: 하루 1시간 ----

    @Test
    public void fcfsAllowsOneHourPerDayAndRejectsTheSecond() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            String a = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            String b = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 0);

            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, a));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, b));
            assertEquals("DAILY_LIMIT", reasonOf(sys, b));
        }
    }

    @Test
    public void fcfsLimitIsPerDayAndPerUser() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            String nextDay = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9 + 24), "k3", 0);
            String otherUser = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R3", 11), "k4", 0);

            assertEquals("다음 날은 새 한도", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, nextDay));
            assertEquals("다른 사람은 영향 없음", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, otherUser));
        }
    }

    @Test
    public void dayIsCountedInKoreanTime() throws Exception {
        // 운영 시간(09~22시) 안에서는 한국 날짜와 UTC 날짜가 같아서, 하루 종일 여는 설정으로 날짜 경계만 확인한다.
        SystemConfig allDay = SystemConfig.defaults().withMinPartySize(1).withRates(100, 5000).withHours(OperatingHours.ALL_DAY);
        try (StudyRoomSystem sys = new StudyRoomSystem(new MutableClock(TestSupport.T0), allDay).start()) {
            // 2026-12-02 14:00Z = 한국 23:00, 15:00Z = 한국 다음 날 00:00. 같은 UTC 날짜지만 한국 시각으로는 다른 날이다.
            SlotId lateNight = new SlotId("R1", TestSupport.T0.plusSeconds(3600L * (24 + 14)));
            SlotId pastMidnight = new SlotId("R2", TestSupport.T0.plusSeconds(3600L * (24 + 15)));
            SlotId pastMidnight2 = new SlotId("R3", TestSupport.T0.plusSeconds(3600L * (24 + 16)));
            String a = TestSupport.reserveAndWait(sys, "u1", lateNight, "k1", 0);
            String b = TestSupport.reserveAndWait(sys, "u1", pastMidnight, "k2", 0);
            String c = TestSupport.reserveAndWait(sys, "u1", pastMidnight2, "k3", 0);

            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, a));
            assertEquals("한국 시각으로 다음 날이라 새 한도", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, b));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, c));
        }
    }

    @Test
    public void cancelingGivesTheHourBackButNoShowDoesNot() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            String a = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 0);
            sys.lifecycle.cancel(a, "USER_CANCELED");
            String again = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R3", 11), "k3", 0);
            assertEquals("직접 취소하면 한도가 돌아온다", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, again));

            // 노쇼로 해제된 예약은 계속 센다.
            String noShow = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R4", 9), "k4", 0);
            sys.lifecycle.cancel(noShow, "NO_SHOW");
            String blocked = TestSupport.reserveAndWait(sys, "u2", TestSupport.slot("R6", 11), "k6", 0);
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, blocked));
        }
    }

    @Test
    public void simultaneousRequestsCannotSlipPastTheLimit() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), FcfsPolicy.NAME)) {
            // 한 사람이 서로 다른 방 8곳에 동시에 신청. 워커 16개가 병렬로 처리해도 확정은 1건뿐이어야 한다.
            for (int i = 0; i < 8; i++) {
                sys.studentApi.reserve("user:u1", TestSupport.slot("RM" + i, 9), "k" + i, 0);
            }
            sys.awaitQuiescent(5000);
            long confirmed = 0;
            for (int i = 0; i < 8; i++) {
                String id = com.studyroom.support.Ids.requestId("u1", "k" + i);
                if (TestSupport.statusOf(sys, id) == ReservationStatus.CONFIRMED) confirmed++;
            }
            assertEquals(1, confirmed);
        }
    }

    // ---- 크레딧 베팅: 하루 2시간 (11/25 결정) ----

    @Test
    public void creditBettingAllowsTwoHoursPerDay() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            String first = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 30);
            String second = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R2", 10), "k2", 20);
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, first));
            assertEquals("같은 날 두 시간까지 입찰할 수 있다", ReservationStatus.REQUESTED, TestSupport.statusOf(sys, second));
            assertEquals(50, sys.credits.balance("u1"));

            String third = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R3", 11), "k3", 10);
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, third));
            assertEquals("세 번째 시간은 하루 한도", "DAILY_LIMIT", reasonOf(sys, third));
            assertEquals("거절된 입찰은 크레딧을 묶지 않는다", 50, sys.credits.balance("u1"));
        }
    }

    @Test
    public void creditBettingIsStillLimitedByBalance() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R1", 9), "k1", 30);
            String tooMuch = TestSupport.reserveAndWait(sys, "u1", TestSupport.slot("R3", 11), "k3", 80);
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, tooMuch));
            assertEquals("INSUFFICIENT_CREDIT", reasonOf(sys, tooMuch));
        }
    }

    // ---- 조회 API ----

    @Test
    public void policyEndpointExposesTheLimit() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, FcfsPolicy.NAME)) {
            QueryApi.PolicyView fcfs = sys.queryApi.policy("user:u1").body();
            assertEquals(Integer.valueOf(1), fcfs.maxSlotsPerDay());
        }
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            assertEquals(Integer.valueOf(2), sys.queryApi.policy("user:u1").body().maxSlotsPerDay());
        }
    }
}
