/**
 * API 오류의 프론트 표현. 화면은 HTTP 상태 코드가 아니라 kind 로 분기한다.
 *
 * 백엔드 오류 계약(ApiResponse 주석):
 *  - 429: Retry-After 만큼 기다렸다 재시도
 *  - 409: 재시도 금지
 *  - 503: 지수 백오프 + 지터로 재시도
 */
export type ApiErrorKind =
  /** 401: 토큰이 없거나 틀림 */
  | 'unauthorized'
  /** 403: 권한 없음(관리자 전용, 남의 예약) */
  | 'forbidden'
  /** 400: 요청 형식 오류 */
  | 'validation'
  /** 404 */
  | 'not_found'
  /** 409 일반 */
  | 'conflict'
  /** 409 (예약): 이미 마감된 슬롯 */
  | 'slot_closed'
  /** 400 (예약): 운영 시간·시험 기간 예약 규칙에 맞지 않음. 서버 문구(언제부터 신청 가능한지)를 그대로 보여 준다 */
  | 'booking_rule'
  /** 409 (예약): 화면을 그린 뒤 배정 정책이 바뀜. 정책을 다시 받아 신청서를 새로 그린다 */
  | 'policy_changed'
  /** 409 (예약): 정책 전환을 기다리는 중이라 마감형 신청을 잠시 받지 않음 */
  | 'policy_switching'
  /** 409 (취소): 취소할 수 없는 상태 */
  | 'not_cancelable'
  /** 409 (정책 전환): 다른 전환이 진행 중이거나, 이미 그 정책임 */
  | 'switch_conflict'
  /** 429 */
  | 'rate_limited'
  /** 503: 큐 적재 실패 등 */
  | 'unavailable'
  /** 그 밖의 5xx */
  | 'server'
  /** 응답을 받지 못함 */
  | 'network'
  /** 제한 시간 초과 */
  | 'timeout'
  /** 호출한 쪽에서 취소함 */
  | 'aborted';

export interface ApiErrorInit {
  readonly kind: ApiErrorKind;
  readonly status?: number;
  /** 백엔드가 준 {"error": "..."} 문구 */
  readonly serverMessage?: string;
  readonly retryAfterMs?: number;
  readonly cause?: unknown;
}

const DEFAULT_COPY: Readonly<Record<ApiErrorKind, string>> = {
  unauthorized: '로그인이 필요합니다.',
  forbidden: '이 작업을 할 권한이 없습니다.',
  validation: '요청 내용을 다시 확인해 주세요.',
  not_found: '대상을 찾을 수 없습니다.',
  conflict: '지금 상태에서는 처리할 수 없습니다.',
  slot_closed: '이미 마감된 시간입니다. 다른 시간을 선택해 주세요.',
  booking_rule: '지금은 이 자리를 신청할 수 없습니다.',
  policy_changed: '배정 규칙이 바뀌었습니다. 바뀐 신청서로 다시 신청해 주세요.',
  policy_switching: '배정 규칙을 바꾸는 중이라 잠시 신청을 받지 않습니다. 잠시 후 다시 시도해 주세요.',
  not_cancelable: '취소할 수 없는 상태의 예약입니다.',
  switch_conflict: '지금은 정책을 바꿀 수 없습니다.',
  rate_limited: '요청이 많습니다. 잠시 후 자동으로 다시 시도합니다.',
  unavailable: '서버가 잠시 요청을 받지 못하고 있습니다. 다시 시도해 주세요.',
  server: '서버에서 오류가 발생했습니다.',
  network: '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
  timeout: '응답이 늦어지고 있습니다. 다시 시도해 주세요.',
  aborted: '요청이 취소되었습니다.',
};

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly serverMessage: string | null;
  readonly retryAfterMs: number | null;

  constructor(init: ApiErrorInit) {
    super(init.serverMessage ?? DEFAULT_COPY[init.kind]);
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status ?? null;
    this.serverMessage = init.serverMessage ?? null;
    this.retryAfterMs = init.retryAfterMs ?? null;
    if (init.cause !== undefined) (this as { cause?: unknown }).cause = init.cause;
  }

  /** 화면에 보여 줄 문구. 서버 문구보다 kind 별 기본 문구를 우선한다(서버 문구는 영어일 수 있다). */
  get userMessage(): string {
    // 예약 규칙 위반은 서버가 "코드:안내"로 언제부터 되는지 알려 준다. 안내 부분을 보여 준다.
    if (this.kind === 'booking_rule' && this.serverMessage) {
      const i = this.serverMessage.indexOf(':');
      return i >= 0 ? this.serverMessage.slice(i + 1) : this.serverMessage;
    }
    return DEFAULT_COPY[this.kind];
  }

  /** 같은 요청을 다시 보내도 되는 오류인가. 409 는 재시도하지 않는다. */
  get retryable(): boolean {
    return (
      this.kind === 'rate_limited' ||
      this.kind === 'unavailable' ||
      this.kind === 'network' ||
      this.kind === 'timeout'
    );
  }

  /** kind 만 바꾼 복사본. 어댑터가 409 를 문맥에 맞는 kind 로 바꿀 때 쓴다. */
  as(kind: ApiErrorKind): ApiError {
    return new ApiError({
      kind,
      status: this.status ?? undefined,
      serverMessage: this.serverMessage ?? undefined,
      retryAfterMs: this.retryAfterMs ?? undefined,
      cause: this,
    });
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

export function toApiError(e: unknown): ApiError {
  if (isApiError(e)) return e;
  return new ApiError({ kind: 'network', cause: e });
}
