import { ApiError, type ApiErrorKind } from '@/domain/errors';
import type { ErrorDto } from '@/api/dto/backend';

/**
 * fetch 를 감싼 HTTP 클라이언트. 화면과 게이트웨이는 fetch 를 직접 부르지 않는다.
 *  - 기준 경로와 Authorization 헤더를 붙인다
 *  - 제한 시간과 취소(AbortSignal)를 처리한다
 *  - 2xx 가 아니면 ApiError 로 바꿔 던진다
 */
export interface HttpClientConfig {
  /** 예: '/api' */
  readonly baseUrl: string;
  /** 요청마다 불러 토큰을 얻는다. 로그인 전이면 null */
  readonly getToken: () => string | null;
  readonly timeoutMs?: number;
  /** 테스트에서 바꿔 끼운다 */
  readonly fetchImpl?: typeof fetch;
}

export interface HttpRequest {
  readonly method: 'GET' | 'POST' | 'DELETE';
  readonly path: string;
  readonly query?: Readonly<Record<string, string | number | undefined>>;
  readonly body?: unknown;
  readonly headers?: Readonly<Record<string, string>>;
  readonly signal?: AbortSignal;
  /** false 면 Authorization 헤더를 붙이지 않는다(QR 기기 웹훅) */
  readonly auth?: boolean;
}

export interface HttpResponse<T> {
  readonly status: number;
  /** 본문이 없으면 null. JSON 이 아니면 문자열 그대로. */
  readonly data: T | null;
  readonly headers: Headers;
}

export interface HttpClient {
  request<T>(req: HttpRequest): Promise<HttpResponse<T>>;
}

const DEFAULT_TIMEOUT_MS = 8000;

function kindOfStatus(status: number): ApiErrorKind {
  if (status === 400 || status === 422) return 'validation';
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404 || status === 405) return 'not_found';
  if (status === 409) return 'conflict';
  if (status === 429) return 'rate_limited';
  if (status === 503 || status === 502 || status === 504) return 'unavailable';
  return 'server';
}

/** Retry-After 헤더(초 또는 HTTP 날짜)를 밀리초로 */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | null {
  if (!value) return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
  const at = Date.parse(value);
  return Number.isNaN(at) ? null : Math.max(0, at - now);
}

/**
 * 본문을 읽는다. 백엔드는 대부분 JSON 을 주지만,
 * 문자열 하나만 돌려주는 응답(전환 번호)은 따옴표 없는 평문으로 온다. 그래서 JSON 해석에 실패하면 문자열로 둔다.
 */
async function readBody(res: Response): Promise<unknown> {
  if (res.status === 204) return null;
  const text = await res.text();
  if (text.length === 0) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function buildUrl(baseUrl: string, path: string, query: HttpRequest['query']): string {
  const base = baseUrl.replace(/\/+$/, '');
  const p = path.startsWith('/') ? path : `/${path}`;
  if (!query) return base + p;
  const qs = Object.entries(query)
    .filter((e): e is [string, string | number] => e[1] !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
  return qs ? `${base}${p}?${qs}` : base + p;
}

export function createHttpClient(config: HttpClientConfig): HttpClient {
  const timeoutMs = config.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return {
    async request<T>(req: HttpRequest): Promise<HttpResponse<T>> {
      const doFetch = config.fetchImpl ?? globalThis.fetch.bind(globalThis);
      const controller = new AbortController();
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, timeoutMs);
      const onOuterAbort = () => controller.abort();
      if (req.signal) {
        if (req.signal.aborted) controller.abort();
        else req.signal.addEventListener('abort', onOuterAbort, { once: true });
      }

      const headers: Record<string, string> = { Accept: 'application/json', ...req.headers };
      if (req.body !== undefined) headers['Content-Type'] = 'application/json';
      if (req.auth !== false) {
        const token = config.getToken();
        if (token) headers['Authorization'] = `Bearer ${token}`;
      }

      let res: Response;
      try {
        res = await doFetch(buildUrl(config.baseUrl, req.path, req.query), {
          method: req.method,
          headers,
          body: req.body === undefined ? undefined : JSON.stringify(req.body),
          signal: controller.signal,
        });
      } catch (cause) {
        if (timedOut) throw new ApiError({ kind: 'timeout', cause });
        if (req.signal?.aborted) throw new ApiError({ kind: 'aborted', cause });
        throw new ApiError({ kind: 'network', cause });
      } finally {
        clearTimeout(timer);
        req.signal?.removeEventListener('abort', onOuterAbort);
      }

      const data = await readBody(res).catch(() => null);
      if (res.ok) return { status: res.status, data: data as T | null, headers: res.headers };

      const dto = (data && typeof data === 'object' ? data : {}) as ErrorDto;
      throw new ApiError({
        kind: kindOfStatus(res.status),
        status: res.status,
        serverMessage: typeof data === 'string' ? data : (dto.error ?? dto.message),
        retryAfterMs: parseRetryAfter(res.headers.get('Retry-After')) ?? undefined,
      });
    },
  };
}
