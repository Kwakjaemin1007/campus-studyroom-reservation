package com.studyroom.control.modifiable.api;

import java.time.LocalDate;
import java.util.List;

/** 시험 기간 달력. 관리자가 등록·취소하고, 화면은 목록으로 예고를 띄운다. */
public interface IExamCalendar {
    /** 등록 결과. canceled는 그 기간에 이미 잡혀 있어 자동 취소한 예약 수 */
    record Registration(ExamPeriod period, int canceled) {
    }

    /**
     * 등록한다. 시작일은 내일 이후, 다른 기간과 겹치면 안 된다.
     * 정책 쓰기 락을 잡고 버전을 올리므로, 그 전에 접수돼 큐에 있던 그 날짜 신청은 새 규칙으로 판정되지 않는다.
     *
     * @throws IllegalArgumentException 날짜가 잘못됐거나 겹친다
     * @throws UnknownPolicyException   없는 정책
     */
    Registration register(LocalDate start, LocalDate end, String policy);

    /** 취소한다. 없으면 false */
    boolean cancel(String id);

    /** 오늘 이후에 끝나는 기간, 시작일 순 */
    List<ExamPeriod> upcoming();
}
