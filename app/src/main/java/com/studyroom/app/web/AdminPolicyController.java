package com.studyroom.app.web;

import com.studyroom.boundary.AdminPolicyApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 관리자용 HTTP 엔드포인트. */
@RestController
public class AdminPolicyController {
    public record SwitchBody(String target) {
    }

    private final AdminPolicyApi api;

    public AdminPolicyController(AdminPolicyApi api) {
        this.api = api;
    }

    @GetMapping("/admin/policy")
    public ResponseEntity<Object> current(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponses.toHttp(api.currentPolicy(ApiResponses.bearer(authorization)));
    }

    @PostMapping("/admin/policy/switch")
    public ResponseEntity<Object> switchPolicy(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody SwitchBody body) {
        return ApiResponses.toHttp(api.switchPolicy(ApiResponses.bearer(authorization), body.target()));
    }

    @GetMapping("/admin/policy/switch/{switchId}")
    public ResponseEntity<Object> status(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String switchId) {
        return ApiResponses.toHttp(api.switchStatus(ApiResponses.bearer(authorization), switchId));
    }

    /** 시험 기간 등록. 본문: {"start":"2026-12-07","end":"2026-12-20","policy":"CREDIT_BETTING"} */
    @PostMapping("/admin/exam-periods")
    public ResponseEntity<Object> registerExamPeriod(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody AdminPolicyApi.ExamPeriodRequest body) {
        return ApiResponses.toHttp(api.registerExamPeriod(ApiResponses.bearer(authorization), body));
    }

    @GetMapping("/admin/exam-periods")
    public ResponseEntity<Object> examPeriods(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponses.toHttp(api.examPeriods(ApiResponses.bearer(authorization)));
    }

    @DeleteMapping("/admin/exam-periods/{id}")
    public ResponseEntity<Object> cancelExamPeriod(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String id) {
        return ApiResponses.toHttp(api.cancelExamPeriod(ApiResponses.bearer(authorization), id));
    }
}
