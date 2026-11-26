package com.studyroom.control.performance.api;

/**
 * 신청이 접수 시점의 정책과 맞지 않는다 (HTTP 409, 같은 내용으로 재시도 금지).
 * 앱은 GET /policy로 지금 정책을 다시 받아 신청 화면을 바꾼 뒤 새로 신청한다.
 */
public class PolicyChangedException extends RuntimeException {
    /** 앱이 본 버전과 지금 버전이 다르다 */
    public static final String POLICY_CHANGED = "POLICY_CHANGED";
    /** 정책 전환이 예약돼 있어 마감형(베팅) 신청을 잠시 받지 않는다 */
    public static final String POLICY_SWITCHING = "POLICY_SWITCHING";

    private final String code;

    public PolicyChangedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
