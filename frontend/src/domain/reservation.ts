/**
 * 예약에 관한 프론트 전용 모델.
 * 백엔드 대응: SlotId, SlotState, ReservationStatus, Ticket.
 */

/** 예약 단위: 방 하나의 한 시간대(SlotId). */
export interface SlotRef {
  readonly roomId: string;
  readonly startsAt: Date;
}

/**
 * 백엔드 SlotId.key() 와 같은 문자열을 만든다: roomId + "@" + 시작 시각(ISO-8601, UTC).
 * Java 의 Instant.toString() 은 밀리초가 0이면 생략하므로 같은 모양으로 맞춘다.
 */
export function slotKey(slot: SlotRef): string {
  return `${slot.roomId}@${slot.startsAt.toISOString().replace('.000Z', 'Z')}`;
}

/**
 * 백엔드의 예약 상태 6가지.
 * REQUESTED → CONFIRMED / REJECTED / CANCELED, CONFIRMED → CHECKED_IN / CANCELED(노쇼 포함), CHECKED_IN → COMPLETED.
 * 주의: 지금 HTTP 응답(Ticket)은 이 값을 그대로 주지 않고 4가지(TicketPhase)로 줄여서 준다.
 */
export type ReservationStatus =
  | 'REQUESTED'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'CHECKED_IN'
  | 'CANCELED'
  | 'COMPLETED';

/**
 * 티켓으로 알 수 있는 상태.
 *  - QUEUED   : Ticket.PENDING. 큐 대기 중이거나, 마감형 정책에서 보류(REQUESTED) 중이다. 둘은 구분되지 않는다.
 *  - CONFIRMED: Ticket.CONFIRMED. 백엔드 상태 CONFIRMED · CHECKED_IN · COMPLETED 가 모두 여기로 온다.
 *  - REJECTED / CANCELED: 끝난 상태. 사유는 reasonCode.
 */
export type TicketPhase = 'QUEUED' | 'CONFIRMED' | 'REJECTED' | 'CANCELED';

/** 백엔드가 reason 으로 주는 값. 처음 보는 값이 올 수 있어 열린 집합으로 둔다. */
export type ReasonCode =
  | 'LOST_FCFS'
  | 'SLOT_TAKEN'
  | 'LOWER_PRIORITY'
  | 'OUTBID'
  | 'NO_BID'
  | 'INSUFFICIENT_CREDIT'
  | 'NO_SHOW'
  | 'DAILY_LIMIT'
  | 'USER_CANCELED'
  | (string & {});

export interface TicketView {
  readonly ticketId: string;
  readonly phase: TicketPhase;
  readonly reasonCode: ReasonCode | null;
  /** 더 바뀌지 않는 상태(거절·취소)면 true. CONFIRMED 는 노쇼로 취소될 수 있어 false 다. */
  readonly settled: boolean;
}

/** 예약 신청에 실어 보내는 값. 정책 UI 모듈이 만든다. */
export interface ReserveInput {
  readonly slot: SlotRef;
  /** 크레딧 베팅 입찰액. 베팅이 아니면 0 */
  readonly bidAmount: number;
}

/**
 * 예약 상세(GET /reservations/{id}). 번호표와 달리 상태 6가지를 그대로 가진다.
 * 확정과 입실을 구분해야 하는 화면(노쇼 카운트다운)이 이것을 쓴다.
 */
export interface ReservationDetail {
  readonly id: string;
  readonly status: ReservationStatus;
  readonly reasonCode: ReasonCode | null;
  readonly slot: SlotRef;
  /** 이 예약을 판정한(또는 접수한) 정책 */
  readonly policyName: string;
  readonly policyVersion: number;
  readonly bidAmount: number;
  readonly chargedAmount: number;
  readonly requestedAt: Date | null;
  readonly updatedAt: Date | null;
  /** 이 시각까지 입실하지 않으면 노쇼로 해제된다(슬롯 시작 + 유예) */
  readonly checkInDeadline: Date | null;
}

/**
 * 화면이 보여 주는 예약의 진행 단계. 번호표와 상세를 합쳐서 정한다.
 *  - QUEUED   : 접수 큐에서 기다리는 중(아직 저장소에 없음)
 *  - REQUESTED: 마감형 정책에서 접수 창이 닫히기를 기다리는 중
 *  - 나머지   : 백엔드 예약 상태 그대로
 */
export type TrackedStatus = 'QUEUED' | ReservationStatus;

export function trackedStatusOf(ticket: TicketView, detail: ReservationDetail | null): TrackedStatus {
  if (detail) return detail.status;
  return ticket.phase;
}

/** 더 바뀌지 않는 단계인가 */
export function isFinalStatus(status: TrackedStatus): boolean {
  return status === 'REJECTED' || status === 'CANCELED' || status === 'COMPLETED';
}

/** 대기열 부하. 전체 슬롯을 합친 대기·처리 중 요청 수이고, 내 앞의 순번이 아니다. */
export interface QueueLoad {
  readonly waiting: number;
}

export interface CreditBalance {
  /** 지금 쓸 수 있는 크레딧 */
  readonly available: number;
  /** 입찰로 묶여 있는 크레딧 */
  readonly held: number;
  /** 다음 갱신 시각. 이때 잔액이 지급량으로 초기화된다(남은 크레딧은 이월되지 않는다). 모르면 null */
  readonly renewsAt: Date | null;
}

/** QR 체크인 결과(TransitionResult + 형식 오류). */
export type CheckInOutcome =
  /** APPLIED: 입실 확정됨 */
  | 'CHECKED_IN'
  /** NO_OP: 이미 입실했거나, 확정 전이거나, 취소·노쇼 해제된 예약. 백엔드는 이 셋을 구분해 주지 않는다. */
  | 'NOT_ALLOWED'
  /** NOT_FOUND: 없는 예약 */
  | 'NOT_FOUND'
  /** 400: QR 형식이 아님 */
  | 'INVALID_QR';

export interface CheckInResult {
  readonly outcome: CheckInOutcome;
  readonly reservationId: string | null;
  readonly at: Date;
}

/** 공통 사유 문구. 정책에 특화된 사유는 각 정책 UI 모듈이 가진다. */
export const COMMON_REASON_COPY: Readonly<Record<string, string>> = {
  SLOT_TAKEN: '다른 예약이 먼저 확정된 시간입니다.',
  NO_SHOW: '시작 후 유예 시간 안에 입실하지 않아 예약이 해제되었습니다.',
  USER_CANCELED: '직접 취소한 예약입니다.',
  DAILY_LIMIT: '하루에 예약할 수 있는 시간을 이미 모두 사용해 접수되지 않았습니다. 다른 날을 고르거나 기존 예약을 취소해 주세요.',
};
