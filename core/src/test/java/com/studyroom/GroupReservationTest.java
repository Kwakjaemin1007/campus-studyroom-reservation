package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.CreditLedger;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.device.modifiable.InMemoryCreditRepository;
import com.studyroom.domain.PartyMember;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 11/19 회의 규칙.
 *  1. 신청자 포함 최소 2명
 *  2. 등록된 학번은 같은 시간에 다른 예약에 들어갈 수 없다
 *  3. 크레딧 베팅의 우선순위는 함께 신청한 사람들이 건 크레딧의 합
 */
public class GroupReservationTest {
    private static StudyRoomSystem system(MutableClock clock, String policy) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withInitialPolicy(policy)).start();
    }

    private static List<PartyMember> with(String... ids) {
        List<PartyMember> out = new ArrayList<>();
        for (String id : ids) out.add(new PartyMember(id, 0));
        return out;
    }

    private static String reserve(StudyRoomSystem sys, String user, SlotId slot, String key, int bid,
                                  List<PartyMember> companions) throws Exception {
        ApiResponse<Ticket> res = sys.studentApi.reserve("user:" + user, slot, key, bid, companions);
        assertEquals(202, res.status());
        sys.awaitQuiescent(5000);
        return res.body().ticketId();
    }

    private static String reasonOf(StudyRoomSystem sys, String id) {
        return sys.repository.findById(id).orElseThrow().reason();
    }

    // ---- 1. 최소 인원 ----

    @Test
    public void aloneOrDuplicatedMembersAreRejectedAtTheDoor() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            SlotId slot = TestSupport.slot("R1", 9);
            assertEquals("혼자는 접수 안 됨", 400, sys.studentApi.reserve("user:a", slot, "k1", 0, List.of()).status());
            assertEquals("자기 자신을 동행으로", 400, sys.studentApi.reserve("user:a", slot, "k2", 0, with("a")).status());
            assertEquals("같은 학번 두 번", 400, sys.studentApi.reserve("user:a", slot, "k3", 0, with("b", "b")).status());
            assertEquals(202, sys.studentApi.reserve("user:a", slot, "k4", 0, with("b")).status());
        }
    }

    // ---- 2. 같은 시간 중복 금지 ----

    @Test
    public void aMemberCannotBeInTwoReservationsAtTheSameTime() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            String first = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 0, with("b"));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, first));

            // b는 9시에 이미 R1에 있다: 다른 방이어도 거절
            String clash = reserve(sys, "c", TestSupport.slot("R2", 9), "k2", 0, with("b"));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, clash));
            assertEquals(AllocationProcessor.MEMBER_TIME_CONFLICT, reasonOf(sys, clash));

            // 신청자로 다시 들어오려 해도 거절
            String asRequester = reserve(sys, "b", TestSupport.slot("R3", 9), "k3", 0, with("d"));
            assertEquals(AllocationProcessor.MEMBER_TIME_CONFLICT, reasonOf(sys, asRequester));

            // 겹치는 사람이 없으면 같은 시간 다른 방은 된다
            String other = reserve(sys, "c", TestSupport.slot("R2", 9), "k4", 0, with("d"));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, other));
        }
    }

    @Test
    public void cancelingFreesTheMembersTime() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "CREDIT_BETTING")) {
            String held = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 10, with("b"));
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, held));
            String clash = reserve(sys, "c", TestSupport.slot("R2", 9), "k2", 10, with("b"));
            assertEquals("접수 중(REQUESTED)인 예약도 시간을 차지한다", AllocationProcessor.MEMBER_TIME_CONFLICT, reasonOf(sys, clash));

            assertEquals(200, sys.studentApi.cancel("user:a", held).status());
            String retry = reserve(sys, "c", TestSupport.slot("R2", 9), "k3", 10, with("b"));
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, retry));
        }
    }

    @Test
    public void concurrentGroupsSharingAMemberGetOnlyOneSeat() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            int n = 8;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiResponse<Ticket>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return sys.studentApi.reserve("user:g" + k, TestSupport.slot("C" + k, 9), "k" + k, 0, with("shared"));
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<ApiResponse<Ticket>> f : futures) ids.add(f.get().body().ticketId());
            pool.shutdown();
            sys.awaitQuiescent(5000);
            long confirmed = ids.stream().filter(id -> TestSupport.statusOf(sys, id) == ReservationStatus.CONFIRMED).count();
            assertEquals("공통 구성원이 있는 8팀 동시 신청 → 확정 1건", 1, confirmed);
        }
    }

    // ---- 하루 한도는 구성원 각자에게 적용 ----

    @Test
    public void fcfsDailyLimitCountsCompanionsToo() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            String first = reserve(sys, "a", TestSupport.slot("R1", 1), "k1", 0, with("b"));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, first));
            String again = reserve(sys, "e", TestSupport.slot("R2", 5), "k2", 0, with("b"));
            assertEquals("b는 동행으로 이미 그날 1시간을 썼다", AllocationProcessor.DAILY_LIMIT, reasonOf(sys, again));
        }
    }

    // ---- 3. 크레딧 합으로 우선순위 ----

    @Test
    public void groupBidIsTheSumOfMemberBids() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            SlotId slot = TestSupport.slot("R1", 9);
            String team = reserve(sys, "a", slot, "k1", 30, List.of(new PartyMember("b", 30)));
            String rival = reserve(sys, "c", slot, "k2", 50, with("d"));

            assertEquals(60, sys.repository.findById(team).orElseThrow().bidAmount());
            assertEquals("각자 자기 몫이 보류된다", 70, sys.credits.balance("a"));
            assertEquals(70, sys.credits.balance("b"));

            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();

            assertEquals("30 + 30 = 60 > 50", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, team));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, rival));
            assertEquals("1등가: 각자 자기 몫을 낸다", 70, sys.credits.balance("a"));
            assertEquals(70, sys.credits.balance("b"));
            assertEquals("탈락은 전액 환불", 100, sys.credits.balance("c"));
            assertEquals(0, sys.credits.openHoldCount());
        }
    }

    @Test
    public void oneShortMemberRejectsTheWholeBid() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), CreditBettingPolicy.NAME)) {
            String id = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 30, List.of(new PartyMember("b", 150)));
            assertEquals("INSUFFICIENT_CREDIT", reasonOf(sys, id));
            assertEquals("a의 몫도 묶이지 않는다(전부 아니면 없음)", 100, sys.credits.balance("a"));
            assertEquals(100, sys.credits.balance("b"));
            assertEquals(0, sys.credits.openHoldCount());
        }
    }

    @Test
    public void secondPriceIsSplitInProportionToShares() {
        CreditLedger ledger = new CreditLedger(new InMemoryCreditRepository(), 100, Clock.systemUTC(), ICreditPeriod.FOREVER);
        assertTrue(ledger.reserve("r1", List.of(new PartyMember("a", 40), new PartyMember("b", 20))));
        ledger.settle("r1", 50); // 50 × 40/60 = 33.3, 50 × 20/60 = 16.6 → 33 + 16 = 49, 남는 1은 몫이 큰 a
        assertEquals(100 - 34, ledger.balance("a"));
        assertEquals(100 - 16, ledger.balance("b"));
    }

    // ---- 조회 ----

    @Test
    public void companionsCanSeeTheReservation() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            String id = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 0, with("b"));
            ApiResponse<QueryApi.ReservationView> seen = sys.queryApi.reservation("user:b", id);
            assertEquals(200, seen.status());
            assertEquals(2, seen.body().members().size());
            assertTrue(seen.body().members().get(0).requester());
            assertEquals(403, sys.queryApi.reservation("user:z", id).status());
            Reservation r = sys.repository.findById(id).orElseThrow();
            assertEquals(List.of("a", "b"), r.memberIds());
        }
    }
}
