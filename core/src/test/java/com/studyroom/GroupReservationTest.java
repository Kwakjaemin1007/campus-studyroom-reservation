package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.CreditLedger;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.device.modifiable.InMemoryCreditRepository;
import com.studyroom.device.modifiable.RecordingNotificationAdapter;
import com.studyroom.domain.NotificationEvent;
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
 *  1. 신청자 포함 최소 2명. 동행 학번은 실제 재학생이어야 하고, 동행 동의 절차는 없다(신청자 책임)
 *  2. 등록된 학번은 같은 시간에 다른 예약에 들어갈 수 없다
 *  3. 팀 합계(입찰액 합)로 순위를 정한다. 같으면 패널티가 적은 팀, 그다음 인원이 많은 팀
 *  4. 크레딧 베팅은 전원이 같은 1인당 금액을 건다(한 사람만 많이 걸 수 없다). 팀 입찰액 = 1인당 × 인원
 *  5. 알림은 등록된 사람 전원에게 간다
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

    private static String reserveAs(StudyRoomSystem sys, String token, SlotId slot, String key, int bid,
                                    List<PartyMember> companions) throws Exception {
        ApiResponse<Ticket> res = sys.studentApi.reserve(token, slot, key, bid, companions);
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

    // ---- 재학생 확인 ----

    @Test
    public void companionsMustBeEnrolledStudents() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            ApiResponse<Ticket> res = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "k1", 0, with("b", "000777"));
            assertEquals(400, res.status());
            assertEquals("어느 학번인지 화면에 알려 준다", "NOT_ENROLLED:000777", res.error());
            assertEquals("접수되지 않았다", 0, sys.repository.all().size());
        }
    }

    // ---- 알림은 전원에게 ----

    @Test
    public void everyMemberIsNotified() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), "FCFS")) {
            String id = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 0, with("b", "c"));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
            assertEquals(3, sys.notifications.count(NotificationEvent.Type.CONFIRMED));
            List<String> to = sys.notifications.sent().stream().map(RecordingNotificationAdapter.Sent::userId).sorted().toList();
            assertEquals(List.of("a", "b", "c"), to);
            String toB = sys.notifications.sent().stream().filter(x -> x.userId().equals("b")).findFirst().orElseThrow().event().message();
            assertTrue("동행에게는 누가 신청했는지 붙인다: " + toB, toB.startsWith("a님이 함께 신청한"));

            assertEquals(200, sys.studentApi.cancel("user:a", id).status());
            assertEquals("취소도 전원에게", 3, sys.notifications.count(NotificationEvent.Type.CANCELED));
        }
    }

    // ---- 크레딧: 전원 같은 1인당 금액 ----

    @Test
    public void teamBidIsPerPersonBidTimesMembers() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            SlotId slot = TestSupport.slot("R1", 9);
            // 동행이 999를 적어 보내도 쓰지 않는다. 전원이 1인당 30을 건다.
            String team = reserve(sys, "a", slot, "k1", 30, List.of(new PartyMember("b", 999)));
            String rival = reserve(sys, "c", slot, "k2", 25, with("d"));

            assertEquals("30 × 2명", 60, sys.repository.findById(team).orElseThrow().bidAmount());
            assertEquals("25 × 2명", 50, sys.repository.findById(rival).orElseThrow().bidAmount());
            assertEquals("각자 1인당 금액만 보류", 70, sys.credits.balance("a"));
            assertEquals(70, sys.credits.balance("b"));

            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();

            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, team));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, rival));
            assertEquals("1등가: 각자 30씩 같은 금액을 낸다", 70, sys.credits.balance("a"));
            assertEquals(70, sys.credits.balance("b"));
            assertEquals("탈락은 전액 환불", 100, sys.credits.balance("c"));
            assertEquals(100, sys.credits.balance("d"));
            assertEquals(0, sys.credits.openHoldCount());
        }
    }

    @Test
    public void oneShortMemberRejectsTheWholeBid() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0), CreditBettingPolicy.NAME)) {
            assertTrue(sys.credits.reserve("b", "elsewhere", 90)); // b의 잔액을 10으로 만든다
            String id = reserve(sys, "a", TestSupport.slot("R1", 9), "k1", 30, with("b"));
            assertEquals("INSUFFICIENT_CREDIT", reasonOf(sys, id));
            assertEquals("a의 몫도 묶이지 않는다(전부 아니면 없음)", 100, sys.credits.balance("a"));
            assertEquals(10, sys.credits.balance("b"));
            assertEquals("남은 보류는 다른 예약 것 하나뿐", 1, sys.credits.openHoldCount());
        }
    }

    @Test
    public void secondPriceIsSplitEqually() {
        CreditLedger ledger = new CreditLedger(new InMemoryCreditRepository(), 100, TestSupport.liveClock(), ICreditPeriod.FOREVER);
        List<PartyMember> three = List.of(new PartyMember("a", 20), new PartyMember("b", 20), new PartyMember("c", 20));
        assertTrue(ledger.reserve("r1", three));
        ledger.settle("r1", 50); // 50 ÷ 3 = 16.7 → 1인당 17로 올림, 셋 다 같은 금액
        assertEquals(83, ledger.balance("a"));
        assertEquals(83, ledger.balance("b"));
        assertEquals(83, ledger.balance("c"));
        assertEquals(0, ledger.openHoldCount());
    }

    // ---- 순위: 합계 → 패널티 → 인원 ----

    @Test
    public void onATieTheTeamWithFewerNoShowsWins() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            // p와 q가 이번 학기에 노쇼를 한 번 냈다.
            SlotId early = TestSupport.slot("R1", 1);
            String noShow = reserve(sys, "p", early, "k0", 10, with("q"));
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, noShow));
            clock.set(early.startsAt().plus(sys.config.grace()).plusSeconds(1));
            sys.scheduler.tick();
            sys.awaitQuiescent(5000);
            assertEquals("NO_SHOW", reasonOf(sys, noShow));

            SlotId slot = TestSupport.slot("R1", 10);
            String penalized = reserve(sys, "p", slot, "k1", 30, with("r"));  // 60, 패널티 1, 먼저 접수
            String clean = reserve(sys, "s", slot, "k2", 30, with("t"));      // 60, 패널티 0
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, clean));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, penalized));
        }
    }

    @Test
    public void onATieWithEqualPenaltiesTheLargerTeamWins() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock, CreditBettingPolicy.NAME)) {
            SlotId slot = TestSupport.slot("R1", 9);
            String pair = reserve(sys, "a", slot, "k1", 30, with("b"));          // 30 × 2 = 60, 먼저 접수
            String trio = reserve(sys, "c", slot, "k2", 20, with("d", "e"));     // 20 × 3 = 60
            clock.advance(sys.config.collectWindow());
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, trio));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, pair));
            assertEquals("세 명이 20씩", 80, sys.credits.balance("e"));
        }
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
