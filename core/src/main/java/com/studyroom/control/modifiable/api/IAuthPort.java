package com.studyroom.control.modifiable.api;

import com.studyroom.domain.Identity;

import java.util.Optional;

/** 학사 SSO 연동 포트. */
public interface IAuthPort {
    /**
     * 로그인 토큰을 검증한다. 토큰은 로컬에서 검증한다(접수 예산 중 20ms).
     *
     * @throws AuthenticationException 토큰이 유효하지 않을 때
     */
    Identity verify(String token);

    /**
     * 학번으로 재학생을 찾는다(회의 결정 11/19: 동행 학번은 실제 재학생이어야 한다).
     * 학적 조회는 네트워크 호출이라 실제 구현은 재학생 명단을 캐시해 접수 예산(300ms)을 지킨다.
     *
     * @return 재학생이면 그 사람의 정보(우선순위 포함), 재학생이 아니면(휴학·졸업·없는 학번) 비어 있음
     * @throws StudentDirectoryUnavailableException 학사 시스템에 물어볼 수 없을 때
     */
    Optional<Identity> lookupStudent(String studentId);
}
