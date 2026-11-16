package com.studyroom.boundary;

/**
 * 창구의 응답. HTTP 상태 코드와 같은 의미를 쓴다.
 * 앱 오류 계약: 429는 Retry-After만큼 기다렸다 재시도, 409는 재시도 금지, 503은 지수 백오프 + 지터.
 */
public record ApiResponse<T>(int status, T body, String error, Integer retryAfterSeconds) {
    public static <T> ApiResponse<T> ok(T body) {
        return new ApiResponse<>(200, body, null, null);
    }

    public static <T> ApiResponse<T> accepted(T body) {
        return new ApiResponse<>(202, body, null, null);
    }

    public static <T> ApiResponse<T> error(int status, String error) {
        return new ApiResponse<>(status, null, error, null);
    }

    public static <T> ApiResponse<T> tooManyRequests(int retryAfterSeconds) {
        return new ApiResponse<>(429, null, "too many requests", retryAfterSeconds);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
