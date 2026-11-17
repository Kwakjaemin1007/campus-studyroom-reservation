import { useCallback, useEffect, useRef, useState } from 'react';
import { FAST_INTERVALS, watchTicket, type TicketWatchEvent } from '@/api';
import { api } from '@/services';
import type { SavedReservation } from '@/state/myReservations';

export interface TrackedReservations {
  /** 번호표 id → 마지막으로 본 상태. 아직 한 번도 못 읽은 번호표는 들어 있지 않다. */
  readonly events: ReadonlyMap<string, TicketWatchEvent>;
  /** 취소처럼 방금 상태를 바꾼 번호표를 기다리지 않고 다시 읽는다 */
  readonly refresh: (ticketId: string) => void;
}

/** 큐 대기가 이보다 길어지면 조회를 멈춘다. 서버가 재시작되어 번호표를 잃어버린 경우다. */
const QUEUED_MAX_WAIT_MS = 60_000;

/**
 * 저장된 번호표마다 감시기(watchTicket)를 하나씩 붙인다.
 * 타임테이블의 "내 예약" 칸과 예약 카드가 같은 값을 보도록 화면 맨 위에서 한 번만 쓴다.
 */
export function useTrackedReservations(saved: readonly SavedReservation[]): TrackedReservations {
  const [events, setEvents] = useState<ReadonlyMap<string, TicketWatchEvent>>(() => new Map());
  const stoppers = useRef(new Map<string, () => void>());

  const start = useCallback((ticketId: string) => {
    stoppers.current.get(ticketId)?.();
    const stop = watchTicket(
      api,
      ticketId,
      (event) => setEvents((prev) => new Map(prev).set(ticketId, event)),
      { intervalsMs: FAST_INTERVALS, confirmedIntervalMs: 4000, maxWaitMs: QUEUED_MAX_WAIT_MS, keepWatchingConfirmed: true },
    );
    stoppers.current.set(ticketId, stop);
  }, []);

  const ids = saved.map((s) => s.ticketId).join('|');

  useEffect(() => {
    const wanted = new Set(ids ? ids.split('|') : []);
    for (const [id, stop] of stoppers.current) {
      if (!wanted.has(id)) {
        stop();
        stoppers.current.delete(id);
      }
    }
    for (const id of wanted) {
      if (!stoppers.current.has(id)) start(id);
    }
    setEvents((prev) => {
      if ([...prev.keys()].every((id) => wanted.has(id))) return prev;
      return new Map([...prev].filter(([id]) => wanted.has(id)));
    });
  }, [ids, start]);

  useEffect(() => {
    const active = stoppers.current;
    return () => {
      for (const stop of active.values()) stop();
      active.clear();
    };
  }, []);

  const refresh = useCallback(
    (ticketId: string) => {
      if (stoppers.current.has(ticketId)) start(ticketId);
    },
    [start],
  );

  return { events, refresh };
}
