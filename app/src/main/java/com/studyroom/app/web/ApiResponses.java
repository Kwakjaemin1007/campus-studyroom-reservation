package com.studyroom.app.web;

import com.studyroom.boundary.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

final class ApiResponses {
    private ApiResponses() {
    }

    /** 코어의 ApiResponse를 HTTP 응답으로 옮긴다. 429에는 Retry-After 헤더를 붙인다. */
    static ResponseEntity<Object> toHttp(ApiResponse<?> r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status());
        if (r.retryAfterSeconds() != null) b.header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfterSeconds()));
        if (r.isSuccess()) return r.body() == null ? b.build() : b.body(r.body());
        return b.body(Map.of("error", r.error() == null ? "error" : r.error()));
    }

    static String bearer(String authorization) {
        if (authorization == null) return null;
        return authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
    }
}
