package com.studyroom.control.modifiable.api;

import java.time.Instant;

/** 크레딧이 갱신되는 기간. 기간이 바뀌면 잔액이 지급량으로 초기화된다. */
public interface ICreditPeriod {
    /** 그 시각이 속한 기간의 이름. 예: "2026-2" */
    String idOf(Instant at);

    /** 그 시각이 속한 기간이 끝나는(= 다음 갱신이 일어나는) 시각 */
    Instant endOf(Instant at);
}
