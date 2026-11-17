import { ApiError, toApiError } from '@/domain/errors';

/**
 * 재시도 규칙. 백엔드 오류 계약을 그대로 옮겼다.
 *  - 429            : Retry-After 만큼 기다렸다 다시
 *  - 503·연결 실패·시간 초과 : 지수 백오프 + 지터
 *  - 409 를 포함한 나머지 : 다시 보내지 않는다
 *
 * 쓰기 요청을 다시 보내도 안전한 이유는 같은 Idempotency-Key 를 쓰기 때문이다(idempotency.ts).
 */
export interface RetryOptions {
  /** 처음 시도를 포함한 최대 횟수 */
  readonly maxAttempts?: number;
  readonly baseDelayMs?: number;
  readonly maxDelayMs?: number;
  readonly signal?: AbortSignal;
  /** 다시 보내기 직전에 불린다. 화면이 "다시 시도하는 중"을 보여 줄 때 쓴다. */
  readonly onRetry?: (info: { attempt: number; delayMs: number; error: ApiError }) => void;
  /** 테스트에서 바꿔 끼운다 */
  readonly sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  readonly random?: () => number;
}

export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new ApiError({ kind: 'aborted' }));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new ApiError({ kind: 'aborted' }));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/** attempt 번째 실패 뒤 기다릴 시간. attempt 는 1부터. */
export function retryDelayMs(
  error: ApiError,
  attempt: number,
  opts: Pick<RetryOptions, 'baseDelayMs' | 'maxDelayMs' | 'random'> = {},
): number {
  const base = opts.baseDelayMs ?? 300;
  const max = opts.maxDelayMs ?? 5000;
  if (error.kind === 'rate_limited') return Math.min(error.retryAfterMs ?? 1000, 30_000);
  const ceiling = Math.min(max, base * 2 ** (attempt - 1));
  // 전체 지터: 0 ~ ceiling 사이에서 고른다. 여러 사용자의 재시도가 같은 순간에 몰리지 않게 한다.
  return Math.floor((opts.random ?? Math.random)() * ceiling);
}

export async function withRetry<T>(run: (attempt: number) => Promise<T>, opts: RetryOptions = {}): Promise<T> {
  const maxAttempts = opts.maxAttempts ?? 4;
  const wait = opts.sleep ?? sleep;
  for (let attempt = 1; ; attempt++) {
    try {
      return await run(attempt);
    } catch (e) {
      const error = toApiError(e);
      if (!error.retryable || attempt >= maxAttempts) throw error;
      const delayMs = retryDelayMs(error, attempt, opts);
      opts.onRetry?.({ attempt, delayMs, error });
      await wait(delayMs, opts.signal);
    }
  }
}
