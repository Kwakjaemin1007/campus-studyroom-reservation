package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.support.CampusTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 학기 단위 갱신. 1학기는 3월 1일, 2학기는 9월 1일 0시(한국 시각)에 시작한다.
 * 실제 학사 일정과 맞추려면 학사 시스템에서 받아 오는 구현으로 바꾼다(★ 팀 미정).
 */
public final class SemesterCreditPeriod implements ICreditPeriod {
    @Override
    public String idOf(Instant at) {
        ZonedDateTime t = at.atZone(CampusTime.ZONE);
        int month = t.getMonthValue();
        if (month >= 3 && month <= 8) return t.getYear() + "-1";
        return (month >= 9 ? t.getYear() : t.getYear() - 1) + "-2";
    }

    @Override
    public Instant endOf(Instant at) {
        ZonedDateTime t = at.atZone(CampusTime.ZONE);
        int month = t.getMonthValue();
        LocalDate next;
        if (month >= 3 && month <= 8) next = LocalDate.of(t.getYear(), 9, 1);
        else if (month >= 9) next = LocalDate.of(t.getYear() + 1, 3, 1);
        else next = LocalDate.of(t.getYear(), 3, 1);
        return CampusTime.startOfDay(next);
    }
}
