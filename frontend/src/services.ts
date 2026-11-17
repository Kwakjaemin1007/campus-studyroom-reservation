import { createApi } from '@/api';
import { browserSessionStorage } from '@/api/http/idempotency';
import { StubSsoSession } from '@/auth/stubSsoSession';
import type { SessionPort } from '@/auth/sessionPort';
import { appConfig } from '@/config';
import { DemoBackend } from '@/demo/demoServer';

/**
 * 앱이 쓰는 인스턴스를 만드는 조립 루트.
 * 학교 SSO 가 연동되면 StubSsoSession 을 실제 구현으로 바꾼다.
 * 데모 빌드에서는 fetch 를 브라우저 안의 데모 서버로 바꾼다(그 밖의 코드는 같다).
 */

export const session: SessionPort = new StubSsoSession(browserSessionStorage());

function localStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** 데모 빌드에서만 만든다. 진짜 서버에 붙는 빌드에서는 null */
export const demoBackend: DemoBackend | null = appConfig.demo ? new DemoBackend({ storage: localStore() }) : null;

export const api = createApi({
  baseUrl: appConfig.apiBase,
  session,
  fetchImpl: demoBackend?.fetch,
});
