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
 *   <li>시험 기간 자리는 미리 잡을 수 없다. 하루 전 운영 종료(22:00)까지 입찰하고 그때 한꺼번에 판정한다.
 *       시작일 자리는 전날이 시험 기간이 아니므로 당일부터 받는다.</li>
 *   <li>시험 기간에도 당일에는 남은 자리를 받는다(10분 마감 방식).</li>
 *   <li>시험 기간이 아닌 날짜는 제한이 없다.</li>
 * </ul>
 */
public final class BookingRules implements IBookingRules {
    private final OperatingHours hours;
    private final IExamPeriodStore exams;

    public BookingRules(OperatingHours hours, IExamPeriodStore exams) {
        this.hours = hours;
        this.exams = exams;
    }

    @Override
    public void check(SlotId slot, Instant now) {
        ZonedDateTime start = slot.startsAt().atZone(CampusTime.ZONE);
        int hour = start.getHour();
        if (start.getMinute() != 0 || hour < hours.open() || hour + 1 > hours.close()) {
            throw new BookingRuleException(BookingRuleException.OUTSIDE_HOURS,
                    String.format("운영 시간(%02d:00~%02d:00) 밖의 자리입니다", hours.open(), hours.close()));
        }
        LocalDate day = start.toLocalDate();
        Optional<ExamPeriod> exam = exams.on(day);
        if (exam.isEmpty()) return;

        LocalDate today = CampusTime.dayOf(now);
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
