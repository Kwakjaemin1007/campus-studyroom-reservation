package com.studyroom.boundary;

import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.ExamPeriod;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.IExamCalendar;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.IPolicySwitch;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.SwitchConflictException;
import com.studyroom.control.modifiable.api.SwitchStatus;
import com.studyroom.control.modifiable.api.UnknownPolicyException;
import com.studyroom.domain.Identity;

import java.time.LocalDate;
import java.util.List;

/** 관리자용 창구 (Boundary). 평소 정책 교체, 전환 상태 조회, 시험 기간 등록. */
public final class AdminPolicyApi {
    public record CurrentPolicy(String name, int version) {
    }

    /** POST /admin/exam-periods 요청 */
    public record ExamPeriodRequest(LocalDate start, LocalDate end, String policy) {
    }

    private final IAuthPort auth;
    private final IPolicySwitch policySwitch;
    private final IPolicyProvider policies;
    private final IExamCalendar exams;

    public AdminPolicyApi(IAuthPort auth, IPolicySwitch policySwitch, IPolicyProvider policies) {
        this(auth, policySwitch, policies, null);
    }

    public AdminPolicyApi(IAuthPort auth, IPolicySwitch policySwitch, IPolicyProvider policies, IExamCalendar exams) {
        this.auth = auth;
        this.policySwitch = policySwitch;
        this.policies = policies;
        this.exams = exams;
    }

    /**
     * POST /admin/exam-periods — 201 + 등록 결과(이미 잡혀 있어 취소한 예약 수 포함).
     * 날짜가 잘못됐거나 겹치면 400, 없는 정책이면 400, 배정이 끝나지 않아 쓰기 락을 못 잡으면 409.
     */
    public ApiResponse<IExamCalendar.Registration> registerExamPeriod(String token, ExamPeriodRequest body) {
        ApiResponse<IExamCalendar.Registration> denied = requireAdmin(token);
        if (denied != null) return denied;
        if (exams == null) return ApiResponse.error(404, "시험 기간 기능이 꺼져 있습니다");
        if (body == null || body.start() == null || body.end() == null || body.policy() == null) {
            return ApiResponse.error(400, "start, end, policy가 필요합니다");
        }
        try {
            return ApiResponse.created(exams.register(body.start(), body.end(), body.policy()));
        } catch (UnknownPolicyException | IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (SwitchConflictException e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /** GET /admin/exam-periods */
    public ApiResponse<List<ExamPeriod>> examPeriods(String token) {
        ApiResponse<List<ExamPeriod>> denied = requireAdmin(token);
        if (denied != null) return denied;
        return ApiResponse.ok(exams == null ? List.of() : exams.upcoming());
    }

    /** DELETE /admin/exam-periods/{id} — 그 날짜는 다시 평소 정책. 이미 베팅으로 잡힌 예약은 그대로 둔다 */
    public ApiResponse<Void> cancelExamPeriod(String token, String id) {
        ApiResponse<Void> denied = requireAdmin(token);
        if (denied != null) return denied;
        try {
            return exams != null && exams.cancel(id) ? ApiResponse.ok(null) : ApiResponse.error(404, "시험 기간이 없습니다");
        } catch (SwitchConflictException e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /** POST /admin/policy/switch — 202 + switchId. 다른 전환이 진행 중이면 409. */
    public ApiResponse<String> switchPolicy(String token, String target) {
        ApiResponse<String> denied = requireAdmin(token);
        if (denied != null) return denied;
        try {
            return ApiResponse.accepted(policySwitch.requestSwitch(target));
        } catch (UnknownPolicyException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (SwitchConflictException e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /** GET /admin/policy/switch/{id} */
    public ApiResponse<SwitchStatus> switchStatus(String token, String switchId) {
        ApiResponse<SwitchStatus> denied = requireAdmin(token);
        if (denied != null) return denied;
        return policySwitch.status(switchId).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(404, "전환 요청이 없습니다"));
    }

    /** GET /admin/policy */
    public ApiResponse<CurrentPolicy> currentPolicy(String token) {
        ApiResponse<CurrentPolicy> denied = requireAdmin(token);
        if (denied != null) return denied;
        PolicyHandle h = policies.current();
        return ApiResponse.ok(new CurrentPolicy(h.name(), h.version()));
    }

    private <T> ApiResponse<T> requireAdmin(String token) {
        try {
            Identity who = auth.verify(token);
            return who.admin() ? null : ApiResponse.error(403, "관리자만 사용할 수 있습니다");
        } catch (AuthenticationException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }
}
