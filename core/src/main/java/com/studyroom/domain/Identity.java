package com.studyroom.domain;

/** 인증 결과. 학사 SSO가 알려 주는 사용자 정보 중 우리가 쓰는 것만 담는다. */
public record Identity(String userId, int priority, boolean admin) {
}
