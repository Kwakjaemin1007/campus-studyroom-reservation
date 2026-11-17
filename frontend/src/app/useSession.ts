import { useSyncExternalStore } from 'react';
import type { Session } from '@/domain/session';
import { session } from '@/services';

/** 로그인 상태를 구독한다. */
export function useSession(): Session | null {
  return useSyncExternalStore(
    (onChange) => session.subscribe(onChange),
    () => session.current(),
  );
}
