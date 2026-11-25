package com.studyroom.control.modifiable.api;

/**
 * 스터디룸 운영 시간(한국 시각, 정시). 이 밖의 슬롯은 예약할 수 없다.
 * close는 마지막 슬롯이 끝나는 시각이자 시험 기간 "내일 자리" 입찰 마감·일괄 판정 시각이다.
 */
public record OperatingHours(int open, int close) {
    /** 11/25 결정: 09:00~22:00 */
    public static final OperatingHours DEFAULT = new OperatingHours(9, 22);
    /** 하루 종일. 시간 계산을 시험하는 테스트용 */
    public static final OperatingHours ALL_DAY = new OperatingHours(0, 24);

    public OperatingHours {
        if (open < 0 || close > 24 || open >= close) throw new IllegalArgumentException("invalid hours");
    }
}
