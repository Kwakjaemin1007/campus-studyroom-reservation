package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.domain.Identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 학사 SSO의 가짜 구현. 실제 연동에서는 SSO가 발급한 JWT의 서명을 로컬에서 검증하고,
 * 재학생 조회는 학적 시스템의 명단(캐시)을 본다.
 * 토큰 형식: "user:{학번}" 또는 "user:{학번}:{우선순위}" 또는 "admin:{id}".
 * 재학생 조회: "000"으로 시작하는 학번은 재학생이 아닌 것으로 본다(휴학·졸업 시험용). 우선순위는 따로 넣은 값, 없으면 0.
 */
public final class StubSsoAuthAdapter implements IAuthPort {
    /** 재학생이 아닌 학번의 접두사(시험용) */
    public static final String NOT_ENROLLED_PREFIX = "000";

    /** 재학생 조회 때 돌려줄 학번별 우선순위(시험용). 실제 구현에서는 학적 시스템이 알려 준다. */
    private final Map<String, Integer> priorities = new ConcurrentHashMap<>();

    /** 시험용: 이 학번을 재학생 조회했을 때 돌려줄 우선순위 */
    public void registerPriority(String studentId, int priority) {
        priorities.put(studentId, priority);
    }

    @Override
    public Identity verify(String token) {
        if (token == null) throw new AuthenticationException("missing token");
        String[] p = token.split(":");
        try {
            if (p.length >= 2 && p[0].equals("user") && !p[1].isBlank()) {
                return new Identity(p[1], p.length >= 3 ? Integer.parseInt(p[2]) : 0, false);
            }
            if (p.length == 2 && p[0].equals("admin") && !p[1].isBlank()) {
                return new Identity(p[1], 0, true);
            }
        } catch (NumberFormatException e) {
            throw new AuthenticationException("invalid token");
        }
        throw new AuthenticationException("invalid token");
    }

    @Override
    public Optional<Identity> lookupStudent(String studentId) {
        if (studentId == null || studentId.isBlank() || studentId.startsWith(NOT_ENROLLED_PREFIX)) return Optional.empty();
        return Optional.of(new Identity(studentId, priorities.getOrDefault(studentId, 0), false));
    }
}
