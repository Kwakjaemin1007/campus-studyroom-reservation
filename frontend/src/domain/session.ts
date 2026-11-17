/** 로그인한 사용자. 백엔드 대응: Identity(userId, priority, admin). */
export interface Session {
  /** Authorization 헤더에 실을 토큰 */
  readonly token: string;
  readonly userId: string;
  /** 우선순위 정책에서 쓰는 값. 클수록 우선. SSO 가 정한다. */
  readonly priority: number;
  readonly role: 'student' | 'admin';
}
