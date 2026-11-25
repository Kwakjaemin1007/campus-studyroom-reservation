package com.studyroom.control.modifiable.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 관리자가 학사 일정을 보고 등록하는 시험 기간. 이 날짜의 자리는 policy로, 나머지 날짜는 평소 정책으로 배정한다.
 * 날짜는 한국 시각 기준이고 start, end 모두 포함한다.
 */
public record ExamPeriod(String id, LocalDate start, LocalDate end, String policy) {
    public ExamPeriod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(policy, "policy");
        if (end.isBefore(start)) throw new IllegalArgumentException("시험 기간의 끝이 시작보다 앞입니다");
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(start) && !day.isAfter(end);
    }

    public boolean overlaps(ExamPeriod o) {
        return !o.end.isBefore(start) && !o.start.isAfter(end);
    }
}
