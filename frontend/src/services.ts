import { createApi } from '@/api';
import { browserSessionStorage } from '@/api/http/idempotency';
import { StubSsoSession } from '@/auth/stubSsoSession';
import type { SessionPort } from '@/auth/sessionPort';
import { appConfig } from '@/config';

/**
 * 앱 전체가 쓰는 서비스 묶음. 구현을 고르는 곳은 여기 한 곳이다.
 * 학교 SSO 가 연동되면 StubSsoSession 을 그 구현으로 바꾼다. 화면 코드는 바뀌지 않는다.
 */
export const session: SessionPort = new StubSsoSession(browserSessionStorage());

export const api = createApi({ baseUrl: appConfig.apiBase, session });
