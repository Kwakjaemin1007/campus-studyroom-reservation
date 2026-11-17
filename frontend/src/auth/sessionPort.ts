import type { Session } from '@/domain/session';

/**
 * 로그인 상태 창구. 백엔드의 IAuthPort 와 짝을 이룬다.
 * 지금은 가짜 SSO 구현(StubSsoSession)이 꽂혀 있고, 학교 SSO 가 연동되면 구현만 바꾼다.
 */
export interface SessionPort {
  current(): Session | null;
  /** @throws Error 토큰 형식이 틀리면 */
  signIn(token: string): Session;
  signOut(): void;
  /** 로그인 상태가 바뀔 때마다 불린다. 돌려주는 함수를 부르면 구독을 끊는다. */
  subscribe(listener: (session: Session | null) => void): () => void;
}
