package com.studyroom.control.modifiable.api;

import java.time.Instant;

/** 크레딧이 갱신되는 기간(학기). 기간이 바뀌면 잔액이 지급량으로 초기화되고, 노쇼 패널티도 이 기간 안의 것만 센다. */
public interface ICreditPeriod {
    /** 그 시각이 속한 기간의 이름. 예: "2026-2" */
    String idOf(Instant at);

    /** 그 시각이 속한 기간이 시작한 시각 */
    Instant startOf(Instant at);

    /** 그 시각이 속한 기간이 끝나는(= 다음 갱신이 일어나는) 시각 */
    Instant endOf(Instant at);

    /** 갱신이 없는 기간(영원히 하나). 테스트·단순 조립용 */
    ICreditPeriod FOREVER = new ICreditPeriod() {
        @Override
        public String idOf(Instant at) {
            return "forever";
        }

        @Override
        public Instant startOf(Instant at) {
            return Instant.EPOCH;
        }

        @Override
        public Instant endOf(Instant at) {
            return Instant.MAX;
        }
    };
}
