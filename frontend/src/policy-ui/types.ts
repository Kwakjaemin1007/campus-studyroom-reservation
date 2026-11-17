import type { ComponentType } from 'react';
import type { Capability } from '@/domain/capability';
import type { ActivePolicy, AllocationTiming, PolicyName } from '@/domain/policy';
import type { CreditBalance, SlotRef } from '@/domain/reservation';
import type { Session } from '@/domain/session';

/**
 * 정책별 UI 모듈의 계약 (프론트의 Strategy).
 *
 * 백엔드의 IAllocationPolicy 가 "요청 묶음 → 판정" 하나로 모든 정책을 같은 모양에 맞춘 것처럼,
 * 프론트는 "신청서 초안 → 검증 → 서버로 보낼 값" 하나로 모든 정책의 예약 화면을 같은 모양에 맞춘다.
 * 예약 드로어는 이 계약만 알고, 어떤 정책이 끼워져 있는지는 모른다.
 *
 * 새 정책이 생기면 modules/ 에 파일 하나를 추가하고 policy-ui/index.ts 에 한 줄을 등록한다.
 * 드로어·타임테이블·어댑터는 고치지 않는다.
 */

/** 모듈이 신청서를 그릴 때 받는 주변 정보 */
export interface PolicyFormContext {
  readonly slot: SlotRef;
  readonly session: Session;
  /** 지금 끼워진 정책. 학생 화면에서 알 수 없으면 null */
  readonly policy: ActivePolicy | null;
  /** needs 에 'credits' 를 적은 모듈에만 채워 준다. 아직 읽는 중이면 null */
  readonly credits: Capability<CreditBalance> | null;
  /** 마감형 정책의 접수 창 길이(분) */
  readonly collectWindowMinutes: number;
}

/** 필드 이름 → 오류 문구. 비어 있으면 통과 */
export type FieldErrors = Readonly<Record<string, string>>;

export interface PolicyFormProps<Draft> {
  readonly draft: Draft;
  readonly onChange: (next: Draft) => void;
  readonly errors: FieldErrors;
  readonly ctx: PolicyFormContext;
  readonly disabled: boolean;
}

/** 서버로 보내는 값 중 정책이 정하는 부분. 지금 백엔드가 받는 것은 bidAmount 하나다. */
export interface PolicySubmission {
  readonly bidAmount: number;
}

export interface StatusCopy {
  readonly title: string;
  readonly body: string;
}

/** 모듈이 드로어에 요구하는 사전 조회 */
export type PolicyNeed = 'credits';

export interface PolicyUIModule<Draft> {
  /** 백엔드 Registry 에 등록된 정책 이름과 같아야 한다 */
  readonly policy: PolicyName;
  /** 화면에 보이는 이름 */
  readonly label: string;
  /** 판정 시점. 백엔드가 알려 주지 않을 때 쓰는 기본값 */
  readonly timing: AllocationTiming;
  /** 한 줄 규칙 */
  readonly rule: string;
  readonly needs: readonly PolicyNeed[];

  createDraft(ctx: PolicyFormContext): Draft;
  validate(draft: Draft, ctx: PolicyFormContext): FieldErrors;
  toSubmission(draft: Draft, ctx: PolicyFormContext): PolicySubmission;

  /** 실행 버튼 문구 */
  submitLabel(draft: Draft, ctx: PolicyFormContext): string;
  /** 번호표가 아직 대기 중일 때 보여 줄 문구 */
  pendingCopy(ctx: PolicyFormContext): StatusCopy;
  /** 이 정책이 내는 거절 사유의 문구 */
  readonly reasonCopy: Readonly<Record<string, string>>;

  /** 신청서 본문 */
  readonly Form: ComponentType<PolicyFormProps<Draft>>;
}

/** 레지스트리에 담을 때는 초안 타입을 지운다. 드로어는 초안을 들여다보지 않고 모듈에 그대로 돌려준다. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyPolicyUIModule = PolicyUIModule<any>;

/** 타입 추론을 돕는 정의 함수 */
export function definePolicyUI<Draft>(module: PolicyUIModule<Draft>): PolicyUIModule<Draft> {
  return module;
}
