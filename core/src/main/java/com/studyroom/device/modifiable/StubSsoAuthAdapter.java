package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.domain.Identity;

/**
 * 학사 SSO의 가짜 구현. 실제 연동에서는 SSO가 발급한 JWT의 서명을 로컬에서 검증한다.
 * 토큰 형식: "user:{학번}" 또는 "user:{학번}:{우선순위}" 또는 "admin:{id}".
 */
public final class StubSsoAuthAdapter implements IAuthPort {
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
}
