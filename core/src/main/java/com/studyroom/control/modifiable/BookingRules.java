package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.BookingRuleException;
import com.studyroom.control.modifiable.api.ExamPeriod;
import com.studyroom.control.modifiable.api.IBookingRules;
import com.studyroom.control.modifiable.api.IExamPeriodStore;
import com.studyroom.control.modifiable.api.OperatingHours;
import com.studyroom.domain.SlotId;
import com.studyroom.support.CampusTime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 예약 규칙 (11/25 결정).
 * <ul>
 *   <li>운영 시간(기본 09:00~22:00) 밖 슬롯은 받지 않는다.</li>
 *   <li>이미 시작한 자리는 받지 않는다.</li>
 *   <li>오늘부터 7일 뒤 날짜까지만 받는다(11/26 결정, 선착순 1주일). 시험 기간 자리는 아래 규칙이 더 좁다.</li>
 *   <li>한 사람이 시작 전 예약을 3개까지만 들고 있을 수 있다(11/26 결정). 세는 일은 배정 단계(PartyGuard)가 한다.</li>
 *   <li>시험 기간 자리는 미리 잡을 수 없다. 하루 전 운영 종료(22:00)까지 입찰하고 그때 한꺼번에 판정한다.
 *       시작일 자리는 전날이 시험 기간이 아니므로 당일부터 받는다.</li>
 *   <li>시험 기간에도 당일에는 남은 자리를 받는다(10분 마감 방식).</li>
 *   <li>시험 기간이 아닌 날짜는 제한이 없다.</li>
 * </ul>
 */
public final class BookingRules implements IBookingRules {
    /** 예약 가능 기간: 오늘부터 며칠 뒤 날짜까지 (11/26 결정: 1주일) */
    public static final int DEFAULT_DAYS_AHEAD = 7;
    /** 한 사람이 들고 있을 수 있는 시작 전 예약 수 (11/26 결정: 3) */
    public static final int DEFAULT_MAX_PENDING = 3;

    private final OperatingHours hours;
    private final IExamPeriodStore exams;
    private final int daysAhead;
    private final int maxPending;

    public BookingRules(OperatingHours hours, IExamPeriodStore exams) {
        this(hours, exams, DEFAULT_DAYS_AHEAD, DEFAULT_MAX_PENDING);
    }

    public BookingRules(OperatingHours hours, IExamPeriodStore exams, int daysAhead, int maxPending) {
        if (daysAhead < 0) throw new IllegalArgumentException("daysAhead");
        if (maxPending < 1) throw new IllegalArgumentException("maxPending");
        this.hours = hours;
        this.exams = exams;
        this.daysAhead = daysAhead;
        this.maxPending = maxPending;
    }

    @Override
    public int maxPendingPerPerson() {
        return maxPending;
    }

    @Override
    public void check(SlotId slot, Instant now) {
        ZonedDateTime start = slot.startsAt().atZone(CampusTime.ZONE);
        int hour = start.getHour();
        if (start.getMinute() != 0 || hour < hours.open() || hour + 1 > hours.close()) {
            throw new BookingRuleException(BookingRuleException.OUTSIDE_HOURS,
                    String.format("운영 시간(%02d:00~%02d:00) 밖의 자리입니다", hours.open(), hours.close()));
        }
        if (!now.isBefore(slot.startsAt())) {
            throw new BookingRuleException(BookingRuleException.SLOT_STARTED, "이미 시작한 시간입니다");
        }
        LocalDate day = start.toLocalDate();
        LocalDate today = CampusTime.dayOf(now);
        Optional<ExamPeriod> exam = exams.on(day);
        if (exam.isEmpty()) {
            // 시험 기간 날짜는 아래 규칙(하루 전부터)이 더 좁고 안내도 더 정확해서 이 검사를 건너뛴다.
            LocalDate last = today.plusDays(daysAhead);
            if (day.isAfter(last)) {
                throw new BookingRuleException(BookingRuleException.TOO_FAR_AHEAD,
                        String.format("%d일 뒤(%d/%d)까지만 예약할 수 있어요", daysAhead, last.getMonthValue(), last.getDayOfMonth()));
            }
            return;
        }

        if (today.equals(day)) return;                                   // 당일 남은 자리, 시작일
        boolean dayBefore = today.equals(day.minusDays(1)) && !day.equals(exam.get().start());
        if (!dayBefore) {
            LocalDate opens = day.equals(exam.get().start()) ? day : day.minusDays(1);
            throw new BookingRuleException(BookingRuleException.EXAM_NOT_OPEN,
                    String.format("시험 기간 자리입니다. %d/%d부터 신청할 수 있어요", opens.getMonthValue(), opens.getDayOfMonth()));
        }
        if (!now.isBefore(closingOf(today))) {
            throw new BookingRuleException(BookingRuleException.EXAM_BIDDING_CLOSED,
                    String.format("내일 자리 입찰은 %02d:00에 마감됐어요. 남은 자리는 내일 %02d:00부터 신청할 수 있어요",
                            hours.close() % 24, hours.open()));
        }
    }

    @Override
    public Instant closeAt(SlotId slot, Instant now, Duration collectWindow) {
        LocalDate day = CampusTime.dayOf(slot.startsAt());
        LocalDate today = CampusTime.dayOf(now);
        if (today.equals(day.minusDays(1)) && exams.on(day).isPresent()) {
            return closingOf(today);                                     // 내일 자리는 오늘 밤 한꺼번에
        }
        Instant closeAt = now.plus(collectWindow);
        return closeAt.isAfter(slot.startsAt()) ? slot.startsAt() : closeAt;
    }

    /** 그날 운영 종료 시각 = 내일 자리 입찰 마감 */
    private Instant closingOf(LocalDate day) {
        return CampusTime.startOfDay(day).plus(Duration.ofHours(hours.close()));
    }
}
