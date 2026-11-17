import { useState } from 'react';
import type { AnyPolicyUIModule, PolicyFormContext } from './types';

/**
 * 신청서 초안을 "그 초안을 만든 모듈"과 묶어서 들고 있는다.
 *
 * 정책이 바뀌어 모듈이 교체되면 초안도 그 자리에서 새 모듈의 것으로 바뀐다.
 * 예전 모듈의 초안이 새 모듈의 validate·Form 에 들어가는 일이 없다.
 * (운영 중 관리자가 정책을 바꾸면 열려 있던 예약 드로어에서 실제로 일어나는 상황이다.)
 */
export function usePolicyDraft(
  module: AnyPolicyUIModule,
  ctx: PolicyFormContext,
): readonly [draft: unknown, setDraft: (next: unknown) => void] {
  const [held, setHeld] = useState(() => ({ module, draft: module.createDraft(ctx) as unknown }));

  let current = held;
  if (held.module !== module) {
    // 렌더 중에 상태를 맞추는 것은 React 가 허용하는 "파생 상태" 방식이다. 화면에 그려지기 전에 다시 렌더된다.
    current = { module, draft: module.createDraft(ctx) as unknown };
    setHeld(current);
  }

  return [current.draft, (next) => setHeld({ module, draft: next })] as const;
}
