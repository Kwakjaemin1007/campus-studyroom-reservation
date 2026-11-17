import { creditBettingPolicyUI } from './modules/creditBetting';
import { fallbackPolicyUI } from './modules/fallback';
import { fcfsPolicyUI } from './modules/fcfs';
import { priorityPolicyUI } from './modules/priority';
import { PolicyUIRegistry } from './registry';

/**
 * 정책 UI 모듈 등록. 새 정책을 추가할 때 고치는 곳은 여기 한 줄과 modules/ 의 새 파일 하나다.
 */
export const policyUIRegistry = new PolicyUIRegistry(fallbackPolicyUI)
  .register(fcfsPolicyUI)
  .register(priorityPolicyUI)
  .register(creditBettingPolicyUI);

export { PolicyUIRegistry } from './registry';
export { usePolicyDraft } from './usePolicyDraft';
export type { ResolvedPolicyUI } from './registry';
export type {
  AnyPolicyUIModule,
  FieldErrors,
  PolicyFormContext,
  PolicyFormProps,
  PolicySubmission,
  PolicyUIModule,
  StatusCopy,
} from './types';
