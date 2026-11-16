package com.studyroom.control.modifiable.api;

import com.studyroom.domain.Identity;

/** 학사 SSO 연동 포트. 토큰은 로컬에서 검증한다(접수 예산 중 20ms). */
public interface IAuthPort {
    /** @throws AuthenticationException 토큰이 유효하지 않을 때 */
    Identity verify(String token);
}
