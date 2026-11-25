package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.ExamPeriod;
import com.studyroom.control.modifiable.api.ICreditPeriod;
import com.studyroom.control.modifiable.api.IExamPeriodStore;
import com.studyroom.support.CampusTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 크레딧 갱신 기간 (11/25 결정: 시험 기간마다 초기화).
 * 시험 기간 안이면 그 기간이 하나의 갱신 단위라 시작할 때 잔액이 지급량으로 채워진다.
 * 중간고사에 다 써도 기말고사에 다시 생긴다. 시험 기간 밖은 학기 단위 그대로다(선착순은 크레딧을 쓰지 않는다).
 * 노쇼 패널티는 이것과 별개로 학기 단위로 센다.
 */
public final class ExamCreditPeriod implements ICreditPeriod {
    private final ICreditPeriod semester;
    private final IExamPeriodStore exams;

    public ExamCreditPeriod(ICreditPeriod semester, IExamPeriodStore exams) {
        this.semester = semester;
        this.exams = exams;
    }

    @Override
    public String idOf(Instant at) {
        return examAt(at).map(p -> "exam-" + p.id()).orElseGet(() -> semester.idOf(at));
    }

    @Override
    public Instant startOf(Instant at) {
        return examAt(at).map(p -> CampusTime.startOfDay(p.start())).orElseGet(() -> semester.startOf(at));
    }

    @Override
    public Instant endOf(Instant at) {
        Optional<ExamPeriod> exam = examAt(at);
        if (exam.isPresent()) return CampusTime.startOfDay(exam.get().end().plusDays(1));
        Instant end = semester.endOf(at);
        LocalDate today = CampusTime.dayOf(at);
        for (ExamPeriod p : exams.all()) {
            Instant s = CampusTime.startOfDay(p.start());
            if (p.start().isAfter(today) && s.isBefore(end)) end = s;
        }
        return end;
    }

    private Optional<ExamPeriod> examAt(Instant at) {
        return exams.on(CampusTime.dayOf(at));
    }
}
