import type {
  CreditBalanceDto,
  CurrentPolicyDto,
  PolicyStatusDto,
  QrScanDto,
  QueueDto,
  ReservationDto,
  SlotStateDto,
  SwitchStatusDto,
  TicketDto,
} from '@/api/dto/backend';
import { phaseOf, type ActivePolicy, type PolicyStatus, type SwitchProgress } from '@/domain/policy';
import {
  slotKey,
  type CheckInOutcome,
  type CreditBalance,
  type QueueLoad,
  type ReservationDetail,
  type TicketPhase,
  type TicketView,
} from '@/domain/reservation';

/** DTO → 프론트 모델 변환. 백엔드 응답 모양이 바뀌면 이 파일만 고친다. */

const TICKET_PHASE: Readonly<Record<TicketDto['status'], TicketPhase>> = {
  PENDING: 'QUEUED',
  CONFIRMED: 'CONFIRMED',
  REJECTED: 'REJECTED',
  CANCELED: 'CANCELED',
};

export function toTicketView(dto: TicketDto): TicketView {
  const phase = TICKET_PHASE[dto.status] ?? 'QUEUED';
  return {
    ticketId: dto.ticketId,
    phase,
    reasonCode: dto.detail ?? null,
    settled: phase === 'REJECTED' || phase === 'CANCELED',
  };
}

export function toActivePolicy(dto: CurrentPolicyDto): ActivePolicy {
  return { name: dto.name, version: dto.version };
}

/** Instant 는 ISO 문자열로도, epoch 초(소수 포함)로도 올 수 있다. */
export function toDate(value: string | number | null | undefined): Date | null {
  if (value === null || value === undefined) return null;
  const d = typeof value === 'number' ? new Date(value * 1000) : new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function toSwitchProgress(dto: SwitchStatusDto): SwitchProgress {
  return {
    switchId: dto.switchId,
    target: dto.target,
    state: dto.state,
    phase: phaseOf(dto.state),
    fromVersion: dto.fromVersion,
    toVersion: dto.toVersion,
    message: dto.message ?? null,
    requestedAt: toDate(dto.requestedAt),
    finishedAt: toDate(dto.finishedAt),
    done: dto.state === 'COMPLETED' || dto.state === 'FAILED',
  };
}

/**
 * 전환 번호. 백엔드는 문자열 하나를 본문으로 주는데, 따옴표 없는 평문("SW-...")으로 온다.
 * JSON 문자열로 바뀌어도 받도록 양쪽 따옴표를 벗긴다.
 */
export function toSwitchId(body: unknown): string {
  if (typeof body === 'string') return body.replace(/^"|"$/g, '').trim();
  if (body && typeof body === 'object' && 'switchId' in body) return String((body as { switchId: unknown }).switchId);
  return '';
}

const CHECK_IN_OUTCOME: Readonly<Record<QrScanDto['result'], CheckInOutcome>> = {
  APPLIED: 'CHECKED_IN',
  NO_OP: 'NOT_ALLOWED',
  NOT_FOUND: 'NOT_FOUND',
};

export function toCheckInOutcome(dto: QrScanDto): CheckInOutcome {
  return CHECK_IN_OUTCOME[dto.result] ?? 'NOT_ALLOWED';
}

export function toPolicyStatus(dto: PolicyStatusDto): PolicyStatus {
  return {
    policy: { name: dto.name, version: dto.version },
    timing: dto.timing ?? null,
    phase: dto.phase ?? null,
    graceSeconds: typeof dto.graceSeconds === 'number' ? dto.graceSeconds : null,
    collectWindowSeconds: typeof dto.collectWindowSeconds === 'number' ? dto.collectWindowSeconds : null,
    maxSlotsPerDay: typeof dto.maxSlotsPerDay === 'number' ? dto.maxSlotsPerDay : null,
  };
}

/** 차 있는 슬롯이면 그 키, 아니면(또는 시각을 읽을 수 없으면) null */
export function toTakenSlotKey(dto: SlotStateDto): string | null {
  const startsAt = toDate(dto.startsAt);
  if (!startsAt || !dto.taken) return null;
  return slotKey({ roomId: dto.roomId, startsAt });
}

export function toCreditBalance(dto: CreditBalanceDto): CreditBalance {
  return { available: dto.balance, held: dto.held };
}

export function toQueueLoad(dto: QueueDto): QueueLoad {
  return { waiting: Math.max(0, dto.depth) };
}

export function toReservationDetail(dto: ReservationDto): ReservationDetail | null {
  const startsAt = toDate(dto.startsAt);
  if (!startsAt) return null;
  return {
    id: dto.id,
    status: dto.status,
    reasonCode: dto.reason ?? null,
    slot: { roomId: dto.roomId, startsAt },
    policyName: dto.policyName,
    policyVersion: dto.policyVersion,
    bidAmount: dto.bidAmount,
    chargedAmount: dto.chargedAmount,
    requestedAt: toDate(dto.requestedAt),
    updatedAt: toDate(dto.updatedAt),
    checkInDeadline: toDate(dto.checkInDeadline),
  };
}
