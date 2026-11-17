/**
 * 백엔드가 주고받는 JSON 그대로의 모양(DTO).
 * 이 파일의 타입은 api/ 안에서만 쓴다. 화면은 domain/ 의 모델만 본다.
 *
 * 출처(app 모듈):
 *  - ReservationController : POST /reservations, GET /tickets/{id}, DELETE /reservations/{id}
 *  - AdminPolicyController : GET /admin/policy, POST /admin/policy/switch, GET /admin/policy/switch/{id}
 *  - QrDeviceController    : POST /devices/qr/scan
 *  - QueryController       : GET /policy, /slots, /credits/me, /reservations/{id}, /queue
 */

/** POST /reservations 본문 (ReservationController.ReserveBody) */
export interface ReserveBodyDto {
  roomId: string;
  /** ISO-8601 UTC 시각 */
  startsAt: string;
  bidAmount?: number | null;
}

/** Ticket(ticketId, status, detail) */
export interface TicketDto {
  ticketId: string;
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELED';
  /** 거절·취소 사유(Reservation.reason). 없으면 null */
  detail: string | null;
}

/** AdminPolicyApi.CurrentPolicy(name, version) */
export interface CurrentPolicyDto {
  name: string;
  version: number;
}

/** POST /admin/policy/switch 본문 (AdminPolicyController.SwitchBody) */
export interface SwitchBodyDto {
  target: string;
}

/** SwitchStatus(switchId, target, state, fromVersion, toVersion, message, requestedAt, finishedAt) */
export interface SwitchStatusDto {
  switchId: string;
  target: string;
  state: 'REQUESTED' | 'DRAINING' | 'COMPLETED' | 'FAILED';
  fromVersion: number;
  toVersion: number;
  message: string | null;
  /** Jackson 설정에 따라 ISO 문자열 또는 epoch 초(숫자)로 온다. */
  requestedAt: string | number | null;
  finishedAt: string | number | null;
}

/** POST /devices/qr/scan 본문 (QrDeviceController.ScanBody) */
export interface QrScanBodyDto {
  /** "RSV:{예약 id}" */
  payload: string;
}

/** POST /devices/qr/scan 응답 */
export interface QrScanDto {
  checkedIn: boolean;
  result: 'APPLIED' | 'NO_OP' | 'NOT_FOUND';
}

/** 오류 응답. 컨트롤러가 만든 것과 Spring 기본 오류 응답 모두 error 문자열을 가진다. */
export interface ErrorDto {
  error?: string;
  message?: string;
}

/* ───────── 조회 API (app 모듈 QueryController → core QueryApi) ───────── */

/** GET /policy — 학생도 읽는 현재 정책 (QueryApi.PolicyView) */
export interface PolicyStatusDto {
  name: string;
  version: number;
  timing?: 'IMMEDIATE' | 'AT_CLOSE' | null;
  phase?: 'ACTIVE' | 'SWITCH_REQUESTED' | 'DRAINING' | null;
  graceSeconds?: number | null;
  collectWindowSeconds?: number | null;
  /** 한 사람이 하루(한국 시각)에 잡을 수 있는 슬롯(=시간) 수. 제한 없으면 null */
  maxSlotsPerDay?: number | null;
}

/** GET /slots?roomId=A,B&from=..&to=.. — 차 있는 슬롯만 온다 (QueryApi.SlotView) */
export interface SlotStateDto {
  roomId: string;
  startsAt: string;
  taken: boolean;
}

/** GET /credits/me (QueryApi.CreditView) */
export interface CreditBalanceDto {
  balance: number;
  held: number;
}

/** GET /reservations/{id} (QueryApi.ReservationView) */
export interface ReservationDto {
  id: string;
  status: 'REQUESTED' | 'CONFIRMED' | 'REJECTED' | 'CHECKED_IN' | 'CANCELED' | 'COMPLETED';
  reason: string | null;
  roomId: string;
  startsAt: string;
  policyName: string;
  policyVersion: number;
  bidAmount: number;
  chargedAmount: number;
  requestedAt: string | number | null;
  updatedAt: string | number | null;
  checkInDeadline: string | number | null;
}

/** GET /queue — 전체 대기·처리 중 요청 수 (QueryApi.QueueView) */
export interface QueueDto {
  depth: number;
}
