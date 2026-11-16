package com.studyroom.boundary;

import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.IPolicySwitch;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.SwitchConflictException;
import com.studyroom.control.modifiable.api.SwitchStatus;
import com.studyroom.control.modifiable.api.UnknownPolicyException;
import com.studyroom.domain.Identity;

/** 관리자용 창구 (Boundary). 정책 교체 요청과 전환 상태 조회. */
public final class AdminPolicyApi {
    public record CurrentPolicy(String name, int version) {
    }

    private final IAuthPort auth;
    private final IPolicySwitch policySwitch;
    private final IPolicyProvider policies;

    public AdminPolicyApi(IAuthPort auth, IPolicySwitch policySwitch, IPolicyProvider policies) {
        this.auth = auth;
        this.policySwitch = policySwitch;
        this.policies = policies;
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
