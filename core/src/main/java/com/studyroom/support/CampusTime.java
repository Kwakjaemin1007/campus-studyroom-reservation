package com.studyroom.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 캠퍼스의 "하루"는 한국 시각 자정부터 자정까지다. 서버는 UTC로 저장하므로 날짜를 셀 때만 이 시간대를 쓴다. */
public final class CampusTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private CampusTime() {
    }

    /** 슬롯 시작 시각이 속한 날(한국 시각 기준). */
    public static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
