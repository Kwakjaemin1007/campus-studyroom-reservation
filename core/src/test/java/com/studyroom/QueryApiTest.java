package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.api.AllocationTiming;
import com.studyroom.control.modifiable.api.SwitchPhase;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 조회 창구(QueryApi): 화면이 읽는 정책·점유 슬롯·크레딧·예약 상세·대기열. */
public class QueryApiTest {
    private static String at(int hour) {
        return TestSupport.slot("x", hour).startsAt().toString();
    }

    @Test
    public void policyShowsTimingPhaseAndTimers() {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults())) {
            assertEquals(401, sys.queryApi.policy("bogus").status());
            QueryApi.PolicyView fcfs = sys.queryApi.policy("user:a").body();
            assertEquals("FCFS", fcfs.name());
            assertEquals(AllocationTiming.IMMEDIATE, fcfs.timing());
            assertEquals(SwitchPhase.ACTIVE, fcfs.phase());
            assertEquals(600, fcfs.graceSeconds());
            assertEquals(600, fcfs.collectWindowSeconds());
        }
        SystemConfig betting = SystemConfig.defaults().withInitialPolicy(CreditBettingPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, betting)) {
            assertEquals(AllocationTiming.AT_CLOSE, sys.queryApi.policy("user:a").body().timing());
        }
    }

    @Test
    public void slotsListOnlyOccupiedOnesInRange() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults()).start()) {
            String mine = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 0);
            TestSupport.reserveAndWait(sys, "b", TestSupport.slot("R2", 10), "k2", 0);
            // 같은 슬롯의 늦은 요청은 조기 거절(409)된다. 목록에는 영향이 없다.
            assertEquals(409, sys.studentApi.reserve("user:c", TestSupport.slot("R1", 9), "k3", 0).status());

            List<QueryApi.SlotView> all = sys.queryApi.slots("user:z", null, at(0), at(24)).body();
            assertEquals(2, all.size());
            assertTrue(all.stream().allMatch(QueryApi.SlotView::taken));

            List<QueryApi.SlotView> r1 = sys.queryApi.slots("user:z", "R1, R9", at(0), at(24)).body();
            assertEquals(1, r1.size());
            assertEquals("R1", r1.get(0).roomId());

            assertEquals("to는 제외", 0, sys.queryApi.slots("user:z", "R1", at(0), at(9)).body().size());
            assertEquals("from은 포함", 1, sys.queryApi.slots("user:z", "R1", at(9), at(10)).body().size());

            assertEquals(400, sys.queryApi.slots("user:z", "R1", "yesterday", at(9)).status());
            assertEquals(400, sys.queryApi.slots("user:z", "R1", null, null).status());
            assertEquals(400, sys.queryApi.slots("user:z", "R1", at(9), at(9)).status());
            assertEquals(400, sys.queryApi.slots("user:z", "R1", at(0), Instant.parse(at(0)).plus(Duration.ofDays(9)).toString()).status());
            assertEquals(401, sys.queryApi.slots(null, "R1", at(0), at(24)).status());

            // 취소하면 목록에서 빠진다.
            assertEquals(200, sys.studentApi.cancel("user:a", mine).status());
            assertEquals(1, sys.queryApi.slots("user:z", null, at(0), at(24)).body().size());
        }
    }

    @Test
    public void reservationDetailKeepsSixStatesAndDeadline() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults()).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            assertEquals("큐에만 있거나 없는 예약은 404", 404, sys.queryApi.reservation("user:a", "R-none").status());

            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            ApiResponse<QueryApi.ReservationView> mine = sys.queryApi.reservation("user:a", id);
            assertEquals(200, mine.status());
            assertEquals(ReservationStatus.CONFIRMED, mine.body().status());
            assertEquals("R1", mine.body().roomId());
            assertEquals(slot.startsAt(), mine.body().startsAt());
            assertEquals("FCFS", mine.body().policyName());
            assertEquals(slot.startsAt().plus(Duration.ofMinutes(10)), mine.body().checkInDeadline());

            assertEquals("남의 예약은 403", 403, sys.queryApi.reservation("user:b", id).status());
            assertEquals("관리자는 볼 수 있다", 200, sys.queryApi.reservation("admin:root", id).status());
            assertEquals(401, sys.queryApi.reservation("bogus", id).status());

            // 번호표는 CONFIRMED로 뭉개지만 상세는 CHECKED_IN을 구분한다.
            sys.qrDevice.onRawScan("RSV:" + id, clock.instant());
            assertEquals(ReservationStatus.CHECKED_IN, sys.queryApi.reservation("user:a", id).body().status());
        }
    }

    @Test
    public void creditsShowBalanceAndHeldBids() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        SystemConfig betting = SystemConfig.defaults().withInitialPolicy(CreditBettingPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, betting).start()) {
            assertEquals(401, sys.queryApi.credits("bogus").status());
            assertEquals(100, sys.queryApi.credits("user:a").body().balance());
            assertEquals(0, sys.queryApi.credits("user:a").body().held());

            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 30);
            assertEquals("입찰 중에는 REQUESTED로 보류", ReservationStatus.REQUESTED, sys.queryApi.reservation("user:a", id).body().status());
            assertEquals(30, sys.queryApi.reservation("user:a", id).body().bidAmount());
            assertEquals(70, sys.queryApi.credits("user:a").body().balance());
            assertEquals(30, sys.queryApi.credits("user:a").body().held());
            assertEquals("남의 보류는 보이지 않는다", 0, sys.queryApi.credits("user:b").body().held());

            assertEquals(200, sys.studentApi.cancel("user:a", id).status());
            assertEquals(100, sys.queryApi.credits("user:a").body().balance());
            assertEquals(0, sys.queryApi.credits("user:a").body().held());
        }
    }

    @Test
    public void queueDepthCountsWaitingRequests() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults())) {
            assertEquals(401, sys.queryApi.queue(null).status());
            assertEquals(0, sys.queryApi.queue("user:a").body().depth());
            // 워커를 아직 띄우지 않았으므로 요청은 큐에 쌓여 있다.
            sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "k1", 0);
            sys.studentApi.reserve("user:b", TestSupport.slot("R2", 9), "k2", 0);
            assertEquals(2, sys.queryApi.queue("user:a").body().depth());
            sys.start();
            sys.awaitQuiescent(5000);
            assertEquals(0, sys.queryApi.queue("user:a").body().depth());
        }
    }
}
