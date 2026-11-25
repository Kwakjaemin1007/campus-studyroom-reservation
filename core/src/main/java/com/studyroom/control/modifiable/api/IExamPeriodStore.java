package com.studyroom.control.modifiable.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 시험 기간 저장소. 메모리 구현과 Redis 구현이 있다. 서버가 여러 대여도 같은 달력을 본다. */
public interface IExamPeriodStore {
    List<ExamPeriod> all();

    void save(ExamPeriod period);

    boolean delete(String id);

    default Optional<ExamPeriod> on(LocalDate day) {
        return all().stream().filter(p -> p.contains(day)).findFirst();
    }
}
