package com.studyroom;

import com.studyroom.boundary.AdminPolicyApi;
import com.studyroom.boundary.ApiResponse;
import com.studyroom.boundary.QueryApi;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.api.BookingRuleException;
import com.studyroom.control.modifiable.api.IExamCalendar;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import com.studyroom.support.CampusTime;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 시험 기간 시나리오 (11/25 결정).
 * 평소에는 선착순, 관리자가 등록한 시험 기간 날짜의 자리는 크레딧 베팅. 자리 날짜로 정책을 고른다.
 */
public class ExamPeriodTest {
    private static final String ADMIN = "admin:root";

    private static Instant kst(int day, int hour) {
        return LocalDate.of(2026, 12, day).atTime(hour, 0).atZone(CampusTime.ZONE).toInstant();
    }

    private static SlotId slot(String room, int day, int hour) {
        return new SlotId(room, kst(day, hour));
    }

    private static StudyRoomSystem system(MutableClock clock) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1).withRates(100, 5000)).start();
    }

    /** 12/7~12/20 크레딧 베팅 */
    private static IExamCalendar.Registration registerExam(StudyRoomSystem sys, int start, int end) {
        ApiResponse<IExamCalendar.Registration> r = sys.adminApi.registerExamPeriod(ADMIN,
                new AdminPolicyApi.ExamPeriodRequest(LocalDate.of(2026, 12, start), LocalDate.of(2026, 12, end), CreditBettingPolicy.NAME));
        assertEquals(r.error(), 201, r.status());
        return r.body();
    }

    private static ApiResponse<Ticket> reserve(StudyRoomSystem sys, String user, SlotId slot, String key, int bid) throws Exception {
        ApiResponse<Ticket> r = sys.studentApi.reserve("user:" + user, slot, key, bid);
        sys.awaitQuiescent(5000);
        return r;
    }

    private static Reservation get(StudyRoomSystem sys, ApiResponse<Ticket> r) {
        return sys.repository.findById(r.body().ticketId()).orElseThrow();
    }

    @Test
    public void registeringCancelsSeatsAlreadyTakenAndTellsMembers() throws Exception {
        MutableClock clock = new MutableClock(kst(3, 10));
        try (StudyRoomSystem sys = system(clock)) {
            ApiResponse<Ticket> exam = reserve(sys, "u1", slot("R1", 8, 14), "k1", 0);    // 등록 전이라 선착순으로 잡힘
            ApiResponse<Ticket> after = reserve(sys, "u2", slot("R1", 10, 14), "k2", 0);  // 시험 끝난 뒤(7일 뒤)
            assertEquals(ReservationStatus.CONFIRMED, get(sys, exam).status());

            IExamCalendar.Registration reg = registerExam(sys, 7, 9);

            assertEquals(1, reg.canceled());
            Reservation canceled = get(sys, exam);
            assertEquals(ReservationStatus.CANCELED, canceled.status());
            assertEquals("EXAM_PERIOD", canceled.reason());
            assertTrue("구성원에게 사유를 알린다", sys.notifications.sent().stream()
                    .anyMatch(s -> s.userId().equals("u1") && s.event().message().contains("시험 기간")));
            assertEquals("시험 기간 밖 날짜는 그대로", ReservationStatus.CONFIRMED, get(sys, after).status());
        }
    }

    @Test
    public void examSeatsOpenOnlyTheDayBeforeAndTheStartDayOnTheDay() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            registerExam(sys, 7, 20);

            ApiResponse<Ticket> early = sys.studentApi.reserve("user:u1", slot("R1", 10, 14), "k1", 10);
            assertEquals(400, early.status());
            assertTrue(early.error(), early.error().startsWith(BookingRuleException.EXAM_NOT_OPEN + ":"));
            assertTrue(early.error().contains("12/9부터"));

            clock.set(kst(6, 10));
            ApiResponse<Ticket> startDay = sys.studentApi.reserve("user:u1", slot("R1", 7, 14), "k2", 10);
            assertEquals("시작일은 전날이 시험 기간이 아니라 당일부터", 400, startDay.status());
            assertTrue(startDay.error().contains("12/7부터"));

            clock.set(kst(7, 10));
            assertEquals(202, reserve(sys, "u1", slot("R1", 7, 14), "k3", 10).status());
            clock.set(kst(9, 10));
            ApiResponse<Ticket> dayBefore = reserve(sys, "u1", slot("R1", 10, 14), "k4", 10);
            assertEquals(202, dayBefore.status());
            assertEquals("시험 기간 자리는 크레딧 베팅", CreditBettingPolicy.NAME, get(sys, dayBefore).policyName());
        }
    }

    @Test
    public void tomorrowBidsCloseAtTenPmAndAreDecidedTogether() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            registerExam(sys, 7, 20);
            clock.set(kst(9, 10));
            SlotId seat = slot("R1", 10, 14);
            ApiResponse<Ticket> low = reserve(sys, "u1", seat, "k1", 30);
            clock.set(kst(9, 18));
            ApiResponse<Ticket> high = reserve(sys, "u2", seat, "k2", 50);

            clock.set(kst(9, 21));
            sys.scheduler.tick();
            assertEquals("22시 전에는 모으기만 한다", ReservationStatus.REQUESTED, get(sys, low).status());

            clock.set(kst(9, 22));
            sys.scheduler.tick();
            assertEquals("늦게 냈어도 높은 입찰이 이긴다", ReservationStatus.CONFIRMED, get(sys, high).status());
            assertEquals(ReservationStatus.REJECTED, get(sys, low).status());
            assertEquals("진 입찰은 환불", 100, sys.credits.balance("u1"));

            ApiResponse<Ticket> late = sys.studentApi.reserve("user:u3", slot("R2", 10, 14), "k3", 10);
            assertEquals(400, late.status());
            assertTrue(late.error().startsWith(BookingRuleException.EXAM_BIDDING_CLOSED + ":"));

            clock.set(kst(10, 9));
            assertEquals("다음 날 남은 자리는 당일에 받는다", 202, reserve(sys, "u3", slot("R2", 10, 14), "k4", 10).status());
        }
    }

    @Test
    public void datesOutsideTheExamPeriodStayFirstComeFirstServed() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            registerExam(sys, 7, 20);
            clock.set(kst(14, 10));
            ApiResponse<Ticket> r = reserve(sys, "u1", slot("R1", 21, 14), "k1", 30);
            Reservation res = get(sys, r);
            assertEquals("시험 기간 중에 신청해도 기간 밖 날짜는 선착순으로 바로 확정", ReservationStatus.CONFIRMED, res.status());
            assertEquals(FcfsPolicy.NAME, res.policyName());
            assertEquals("선착순은 입찰을 쓰지 않는다", 0, res.bidAmount());
            assertEquals(100, sys.credits.balance("u1"));
        }
    }

    @Test
    public void bookingOpensOnlyUpToSevenDaysAhead() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            assertEquals("7일 뒤 날짜까지는 받는다", 202, reserve(sys, "u1", slot("R1", 8, 21), "k1", 0).status());
            ApiResponse<Ticket> far = sys.studentApi.reserve("user:u2", slot("R1", 9, 9), "k2", 0);
            assertEquals(400, far.status());
            assertTrue(far.error(), far.error().startsWith(BookingRuleException.TOO_FAR_AHEAD + ":"));
            assertTrue(far.error().contains("12/8"));
        }
    }

    @Test
    public void startedSlotsAreAcceptedUntilTwentyMinutesBeforeTheEnd() throws Exception {
        MutableClock clock = new MutableClock(kst(2, 14).plus(java.time.Duration.ofMinutes(40)));
        try (StudyRoomSystem sys = system(clock)) {
            assertEquals("남은 20분이면 받는다", 202, reserve(sys, "u1", slot("R1", 2, 14), "k1", 0).status());
            clock.set(kst(2, 14).plus(java.time.Duration.ofMinutes(41)));
            ApiResponse<Ticket> ending = sys.studentApi.reserve("user:u2", slot("R2", 2, 14), "k2", 0);
            assertEquals(400, ending.status());
            assertTrue(ending.error().startsWith(BookingRuleException.SLOT_ENDING + ":"));
        }
    }

    @Test
    public void releasedExamSeatIsFirstComeFirstServedOnceItStarted() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            registerExam(sys, 7, 20);
            SlotId seat = slot("R1", 10, 14);
            clock.set(kst(9, 10));
            ApiResponse<Ticket> won = reserve(sys, "u1", seat, "k1", 30);
            clock.set(kst(9, 22));
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CONFIRMED, get(sys, won).status());

            // 14:10 노쇼로 풀린다
            clock.set(kst(10, 14).plus(java.time.Duration.ofMinutes(10)));
            sys.scheduler.tick();
            sys.awaitQuiescent(5000);
            assertEquals("NO_SHOW", get(sys, won).reason());

            // 이미 시작한 자리라 시험 기간이어도 선착순: 입찰 없이 바로 확정, 크레딧을 쓰지 않는다
            clock.set(kst(10, 14).plus(java.time.Duration.ofMinutes(15)));
            ApiResponse<Ticket> late = reserve(sys, "u2", seat, "k2", 40);
            Reservation res = get(sys, late);
            assertEquals(ReservationStatus.CONFIRMED, res.status());
            assertEquals(FcfsPolicy.NAME, res.policyName());
            assertEquals(0, res.bidAmount());
            assertEquals(100, sys.credits.balance("u2"));

            // 시작 전 당일 남은 자리는 그대로 베팅
            ApiResponse<Ticket> later = reserve(sys, "u3", slot("R1", 10, 16), "k3", 10);
            assertEquals(CreditBettingPolicy.NAME, get(sys, later).policyName());
            assertEquals(ReservationStatus.REQUESTED, get(sys, later).status());
        }
    }

    @Test
    public void onlyOperatingHoursCanBeBooked() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            ApiResponse<Ticket> early = sys.studentApi.reserve("user:u1", slot("R1", 2, 8), "k1", 0);
            assertEquals(400, early.status());
            assertTrue(early.error().startsWith(BookingRuleException.OUTSIDE_HOURS + ":"));
            assertEquals(400, sys.studentApi.reserve("user:u1", slot("R1", 2, 22), "k2", 0).status());
            assertEquals("마지막 슬롯은 21시", 202, sys.studentApi.reserve("user:u1", slot("R1", 2, 21), "k3", 0).status());
        }
    }

    @Test
    public void creditsResetAtEachExamPeriod() throws Exception {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            registerExam(sys, 7, 9);      // 중간고사라고 치자
            registerExam(sys, 14, 16);    // 기말고사
            clock.set(kst(8, 10));
            ApiResponse<Ticket> win = reserve(sys, "u1", slot("R1", 9, 14), "k1", 60);
            clock.set(kst(8, 22));
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CONFIRMED, get(sys, win).status());
            assertEquals(40, sys.credits.balance("u1"));

            clock.set(kst(15, 10));
            assertEquals("다음 시험 기간에는 다시 100", 100, sys.credits.balance("u1"));
        }
    }

    @Test
    public void registrationIsValidatedAndAdminOnly() {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            AdminPolicyApi.ExamPeriodRequest today = new AdminPolicyApi.ExamPeriodRequest(
                    LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), CreditBettingPolicy.NAME);
            assertEquals("오늘 시작은 안 된다", 400, sys.adminApi.registerExamPeriod(ADMIN, today).status());
            assertEquals(403, sys.adminApi.registerExamPeriod("user:u1", new AdminPolicyApi.ExamPeriodRequest(
                    LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 9), CreditBettingPolicy.NAME)).status());
            assertEquals(400, sys.adminApi.registerExamPeriod(ADMIN, new AdminPolicyApi.ExamPeriodRequest(
                    LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 9), "NOPE")).status());
            registerExam(sys, 7, 20);
            assertEquals("겹치면 안 된다", 400, sys.adminApi.registerExamPeriod(ADMIN, new AdminPolicyApi.ExamPeriodRequest(
                    LocalDate.of(2026, 12, 19), LocalDate.of(2026, 12, 22), CreditBettingPolicy.NAME)).status());
        }
    }

    @Test
    public void policyViewTellsTheAppAboutUpcomingPeriodsAndHours() {
        MutableClock clock = new MutableClock(kst(1, 10));
        try (StudyRoomSystem sys = system(clock)) {
            int before = sys.queryApi.policy("user:u1").body().version();
            String id = registerExam(sys, 7, 20).period().id();
            QueryApi.PolicyView v = sys.queryApi.policy("user:u1").body();
            assertEquals(FcfsPolicy.NAME, v.name());
            assertEquals("등록하면 버전이 오른다(앱은 신청서를 다시 그린다)", before + 1, v.version());
            assertEquals(9, v.openHour());
            assertEquals(22, v.closeHour());
            assertEquals(1, v.examPeriods().size());
            assertEquals(CreditBettingPolicy.NAME, v.examPeriods().get(0).policy());
            assertEquals(Integer.valueOf(2), v.examPeriods().get(0).maxSlotsPerDay());

            assertEquals(200, sys.adminApi.cancelExamPeriod(ADMIN, id).status());
            assertEquals(0, sys.queryApi.policy("user:u1").body().examPeriods().size());
        }
    }
}
