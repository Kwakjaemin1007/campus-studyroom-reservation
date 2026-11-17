import { useCallback, useEffect, useRef, useState } from 'react';
import { toApiError, type ApiError } from '@/domain/errors';

export interface PollState<T> {
  /** 마지막으로 성공한 값. 아직 한 번도 못 읽었으면 null */
  readonly data: T | null;
  /** 마지막 조회의 오류. 성공하면 null 로 돌아온다 */
  readonly error: ApiError | null;
  /** 첫 값을 기다리는 중 */
  readonly loading: boolean;
  /** 다음 주기를 기다리지 않고 지금 다시 읽는다 */
  readonly refresh: () => void;
}

export interface PollOptions {
  readonly intervalMs: number;
  /** 값이 바뀌면 처음부터 다시 읽는다(예: 건물·날짜). 이전 값은 버린다. */
  readonly key: string;
  readonly enabled?: boolean;
}

/**
 * 일정 간격으로 조회한다. 백엔드에 푸시 채널이 없어 화면은 폴링으로 최신 상태를 따라간다.
 * 탭이 가려져 있으면 쉬고, 오류가 나도 마지막 값을 화면에 남긴 채 다음 주기에 다시 시도한다.
 */
export function usePolling<T>(load: (signal: AbortSignal) => Promise<T>, options: PollOptions): PollState<T> {
  const { intervalMs, key, enabled = true } = options;
  const loadRef = useRef(load);
  loadRef.current = load;

  const [state, setState] = useState<{ key: string; data: T | null; error: ApiError | null; loading: boolean }>({
    key,
    data: null,
    error: null,
    loading: enabled,
  });
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let timer: number | undefined;

    const run = async () => {
      if (!document.hidden) {
        try {
          const data = await loadRef.current(controller.signal);
          if (controller.signal.aborted) return;
          setState({ key, data, error: null, loading: false });
        } catch (e) {
          const error = toApiError(e);
          if (controller.signal.aborted || error.kind === 'aborted') return;
          setState((prev) => ({ key, data: prev.key === key ? prev.data : null, error, loading: false }));
        }
      }
      timer = window.setTimeout(() => void run(), intervalMs);
    };
    void run();

    return () => {
      controller.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [enabled, intervalMs, key, tick]);

  // 키가 바뀐 직후의 렌더에서는 이전 키의 값을 보여 주지 않는다.
  if (state.key !== key) return { data: null, error: null, loading: enabled, refresh };
  return { data: state.data, error: state.error, loading: state.loading, refresh };
}
