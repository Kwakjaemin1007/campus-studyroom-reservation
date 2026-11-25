/**
 * 배정 정책에 관한 프론트 전용 모델.
 * 백엔드 대응: ActivePolicy(name, version), AllocationTiming, SwitchPhase, SwitchStatus.State.
 */

/** 지금 백엔드 Registry 에 등록된 정책 이름. */
export type KnownPolicyName = 'FCFS' | 'PRIORITY' | 'CREDIT_BETTING';

/**
 * 정책 이름은 열린 집합이다. 백엔드에 새 정책이 등록되면 처음 보는 이름이 올 수 있고,
 * 그때는 PolicyUIRegistry 가 대체 모듈로 받는다.
 */
export type PolicyName = KnownPolicyName | (string & {});

/** IMMEDIATE: 요청이 오는 즉시 판정. AT_CLOSE: 접수 창이 닫힐 때 한꺼번에 판정. */
export type AllocationTiming = 'IMMEDIATE' | 'AT_CLOSE';

export interface ActivePolicy {
  readonly name: PolicyName;
  readonly version: number;
}

/** 전환 상태 기계. ACTIVE → SWITCH_REQUESTED → DRAINING → ACTIVE */
export type SwitchPhase = 'ACTIVE' | 'SWITCH_REQUESTED' | 'DRAINING';

export const SWITCH_PHASES: readonly SwitchPhase[] = ['ACTIVE', 'SWITCH_REQUESTED', 'DRAINING'];

/** 전환 요청 한 건의 진행 상태(SwitchStatus.State). */
export type SwitchState = 'REQUESTED' | 'DRAINING' | 'COMPLETED' | 'FAILED';

export interface SwitchProgress {
  readonly switchId: string;
  readonly target: PolicyName;
  readonly state: SwitchState;
  /** state 에서 계산한 전환 상태 기계의 위치. 백엔드는 phase 를 HTTP 로 직접 내보내지 않는다. */
  readonly phase: SwitchPhase;
  readonly fromVersion: number;
  readonly toVersion: number;
  /** 실패 사유. 성공이면 null */
  readonly message: string | null;
  readonly requestedAt: Date | null;
  readonly finishedAt: Date | null;
  /** COMPLETED 또는 FAILED 면 true. 더 조회할 필요가 없다. */
  readonly done: boolean;
}

export function phaseOf(state: SwitchState): SwitchPhase {
  switch (state) {
    case 'REQUESTED':
      return 'SWITCH_REQUESTED';
    case 'DRAINING':
      return 'DRAINING';
    case 'COMPLETED':
    case 'FAILED':
      return 'ACTIVE';
  }
}

/** 학생 화면이 알아야 하는 정책 상태. 정책 이름, 판정 시점, 전환 진행 여부. */
export interface PolicyStatus {
  readonly policy: ActivePolicy;
  /** 백엔드가 알려 주지 않으면 null. 그때는 UI 모듈이 가진 값을 쓴다. */
  readonly timing: AllocationTiming | null;
  /** 백엔드가 알려 주지 않으면 null. */
  readonly phase: SwitchPhase | null;
  /** 노쇼 유예(초). 슬롯 시작 뒤 이 시간 안에 입실해야 한다. 모르면 null */
  readonly graceSeconds: number | null;
  /** 마감형 정책의 접수 창 길이(초). 모르면 null */
  readonly collectWindowSeconds: number | null;
  /** 한 사람이 하루(한국 시각)에 잡을 수 있는 시간 수(슬롯 1개 = 1시간). 제한이 없거나 모르면 null */
  readonly maxSlotsPerDay: number | null;
  /** 신청자 포함 최소 인원(11/19 회의: 2명). 옛 서버처럼 모르면 null */
  readonly minPartySize: number | null;
  /** 운영 시간(KST 시). 모르면 null */
  readonly openHour: number | null;
  /** 운영 종료 시각(KST 시). 시험 기간 '내일 자리' 입찰 마감 시각이기도 하다 */
  readonly closeHour: number | null;
  /** 오늘 이후에 끝나는 시험 기간. 이 날짜의 자리는 그 기간의 정책을 따른다(자리 날짜 기준) */
  readonly examPeriods: readonly ExamPeriodInfo[];
}

/** 관리자가 등록한 시험 기간. 날짜는 'YYYY-MM-DD'(KST), 시작·끝 포함 */
export interface ExamPeriodInfo {
  readonly id: string;
  readonly start: string;
  readonly end: string;
  readonly policy: PolicyName;
  readonly timing: AllocationTiming | null;
  readonly maxSlotsPerDay: number | null;
}

/** 그 날짜가 속한 시험 기간 */
export function examPeriodOn(dateKey: string, periods: readonly ExamPeriodInfo[]): ExamPeriodInfo | null {
  return periods.find((p) => p.start <= dateKey && dateKey <= p.end) ?? null;
}

/**
 * 시험 기간 자리를 지금 신청할 수 있는지 미리 알려 주는 문구(판단은 서버가 한다. 서버 BookingRules 와 같은 규칙).
 * 하루 전 운영 종료(22시)까지 입찰, 시작일은 당일부터, 당일에는 남은 자리를 받는다. 신청할 수 있으면 null.
 */
export function examBookingHint(
  dateKey: string,
  todayKey: string,
  now: Date,
  period: ExamPeriodInfo | null,
  closeAt: Date,
): string | null {
  if (!period || dateKey === todayKey) return null;
  const opens = dateKey === period.start ? dateKey : shiftKey(dateKey, -1);
  if (todayKey < opens) return `시험 기간 자리입니다. ${shortDate(opens)}부터 신청할 수 있어요.`;
  if (todayKey === opens && now >= closeAt) return '내일 자리 입찰은 마감됐어요. 남은 자리는 내일 당일에 신청할 수 있어요.';
  return null;
}

function shiftKey(dateKey: string, days: number): string {
  const d = new Date(`${dateKey}T12:00:00+09:00`);
  return new Date(d.getTime() + days * 86_400_000).toISOString().slice(0, 10);
}

function shortDate(dateKey: string): string {
  const [, m, d] = dateKey.split('-');
  return `${Number(m)}/${Number(d)}`;
}
