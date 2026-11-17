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
}
