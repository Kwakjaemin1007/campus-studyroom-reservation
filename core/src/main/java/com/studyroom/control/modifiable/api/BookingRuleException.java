package com.studyroom.control.modifiable.api;

/** 예약 규칙상 지금 받을 수 없는 신청 (HTTP 400, 같은 내용으로 재시도 금지). 메시지는 학생에게 그대로 보여 준다. */
public class BookingRuleException extends RuntimeException {
    /** 운영 시간 밖 슬롯 */
    public static final String OUTSIDE_HOURS = "OUTSIDE_HOURS";
    /** 시험 기간 자리를 아직 예약할 수 없다(하루 전부터, 시작일은 당일부터) */
    public static final String EXAM_NOT_OPEN = "EXAM_NOT_OPEN";
    /** 시험 기간 내일 자리 입찰이 오늘 운영 종료로 마감됐다. 남은 자리는 내일 당일에 */
    public static final String EXAM_BIDDING_CLOSED = "EXAM_BIDDING_CLOSED";

    private final String code;

    public BookingRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
