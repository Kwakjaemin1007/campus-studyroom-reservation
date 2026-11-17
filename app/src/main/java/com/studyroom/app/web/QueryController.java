package com.studyroom.app.web;

import com.studyroom.boundary.QueryApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 조회용 HTTP 엔드포인트. 화면이 타임테이블·내 예약·크레딧을 그릴 때 읽는다. 판단은 QueryApi(Boundary)에 맡긴다. */
@RestController
public class QueryController {
    private final QueryApi api;

    public QueryController(QueryApi api) {
        this.api = api;
    }

    @GetMapping("/policy")
    public ResponseEntity<Object> policy(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponses.toHttp(api.policy(ApiResponses.bearer(authorization)));
    }

    /** roomId는 쉼표로 여러 개를 줄 수 있다. from·to는 ISO-8601 시각 문자열 그대로 받아 QueryApi가 검증한다. */
    @GetMapping("/slots")
    public ResponseEntity<Object> slots(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam(value = "roomId", required = false) String roomId,
                                        @RequestParam(value = "from", required = false) String from,
                                        @RequestParam(value = "to", required = false) String to) {
        return ApiResponses.toHttp(api.slots(ApiResponses.bearer(authorization), roomId, from, to));
    }

    @GetMapping("/credits/me")
    public ResponseEntity<Object> credits(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponses.toHttp(api.credits(ApiResponses.bearer(authorization)));
    }

    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<Object> reservation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String reservationId) {
        return ApiResponses.toHttp(api.reservation(ApiResponses.bearer(authorization), reservationId));
    }

    @GetMapping("/queue")
    public ResponseEntity<Object> queue(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponses.toHttp(api.queue(ApiResponses.bearer(authorization)));
    }
}
