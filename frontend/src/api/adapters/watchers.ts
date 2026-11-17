import { sleep } from '@/api/http/retry';
import type { AdminPolicyGateway, QueueGateway, ReservationGateway } from '@/api/ports';
import { toApiError, type ApiError } from '@/domain/errors';
import type { SwitchProgress } from '@/domain/policy';
import {
  isFinalStatus,
  trackedStatusOf,
  type QueueLoad,
  type ReservationDetail,
  type TicketView,
  type TrackedStatus,
} from '@/domain/reservation';

/**
 * 폴링으로 상태 변화를 지켜보는 도구. 백엔드는 푸시 채널이 없고 번호표를 폴링하게 되어 있다
 * (StudentReservationApi: "결과는 getTicket 으로 폴링하거나 푸시로 받는다").
 */

export interface TicketWatchEvent {
  readonly ticket: TicketView;
  /** 예약 상세. 아직 큐에 있거나 서버가 주지 않으면 null */
  readonly detail: ReservationDetail | null;
  /** 번호표와 상세를 합친 진행 단계 */
  readonly status: TrackedStatus;
  /** 대기열 부하. 큐에서 기다리는 동안에만 채운다. 서버가 알려 주지 않으면 null (지어내지 않는다) */
  readonly queue: QueueLoad | null;
  /** 지켜보기 시작한 뒤 흐른 시간 */
  readonly waitedMs: number;
  /** 마지막 조회가 실패해서 화면 값이 오래됐을 수 있으면 true */
  readonly stale: boolean;
  readonly lastError: ApiError | null;
  /** maxWaitMs 를 넘겨 조회를 멈췄으면 true */
  readonly timedOut: boolean;
}

export interface TicketWatchOptions {
  /** 판정을 기다리는 동안의 조회 간격(ms). 앞에서부터 쓰고, 다 쓰면 마지막 값을 반복한다. */
  readonly intervalsMs?: readonly number[];
  /** 확정된 뒤(입실·노쇼 해제·완료를 기다리는 동안)의 조회 간격(ms) */
  readonly confirmedIntervalMs?: number;
  /**
   * 이 시간이 지나도 큐 대기(QUEUED)면 멈춘다.
   * 백엔드는 모르는 번호표 id 에도 PENDING 을 돌려주므로(서버 재시작 후 등) 끝없이 조회하지 않게 막는다.
   */
  readonly maxWaitMs?: number;
  /** CONFIRMED 뒤에도 계속 볼지. 입실(CHECKED_IN)과 노쇼 해제(CANCELED)를 잡으려면 true */
  readonly keepWatchingConfirmed?: boolean;
  readonly now?: () => number;
}

/** 즉시 판정 정책용: 처음엔 촘촘히, 점점 느리게 */
export const FAST_INTERVALS: readonly number[] = [400, 600, 1000, 1500, 2500];
/** 마감 판정 정책용: 접수 창이 닫힐 때까지 몇 분을 기다린다 */
export const SLOW_INTERVALS: readonly number[] = [1000, 2000, 5000];

/**
 * 번호표를 끝날 때까지 지켜본다. 돌려주는 함수를 부르면 멈춘다.
 * 매 회 번호표와 예약 상세를 읽고, 큐에서 기다리는 동안에는 대기열 부하도 읽는다.
 */
export function watchTicket(
  deps: { reservations: ReservationGateway; queue: QueueGateway },
  ticketId: string,
  listener: (event: TicketWatchEvent) => void,
  options: TicketWatchOptions = {},
): () => void {
  const intervals = options.intervalsMs ?? FAST_INTERVALS;
  const confirmedIntervalMs = options.confirmedIntervalMs ?? 5000;
  const maxWaitMs = options.maxWaitMs ?? 15 * 60_000;
  const now = options.now ?? Date.now;
  const controller = new AbortController();
  const startedAt = now();

  void (async () => {
    let ticket: TicketView = { ticketId, phase: 'QUEUED', reasonCode: null, settled: false };
    let detail: ReservationDetail | null = null;
    let queue: QueueLoad | null = null;
    let queueSupported = true;

    for (let round = 0; !controller.signal.aborted; round++) {
      let lastError: ApiError | null = null;
      try {
        ticket = await deps.reservations.getTicket(ticketId, { signal: controller.signal });
        detail = (await deps.reservations.getDetail(ticketId, { signal: controller.signal })) ?? detail;
        if (trackedStatusOf(ticket, detail) === 'QUEUED' && queueSupported) {
          const load = await deps.queue.getLoad({ signal: controller.signal });
          if (load.supported) queue = load.value;
          else queueSupported = false;
        } else {
          queue = null;
        }
      } catch (e) {
        lastError = toApiError(e);
        if (lastError.kind === 'aborted') return;
      }
      if (controller.signal.aborted) return;

      const status = trackedStatusOf(ticket, detail);
      const waitedMs = now() - startedAt;
      const confirmed = ticket.phase === 'CONFIRMED';
      const finished = ticket.settled || isFinalStatus(status) || (confirmed && !options.keepWatchingConfirmed);
      const timedOut = !finished && status === 'QUEUED' && waitedMs >= maxWaitMs;
      listener({ ticket, detail, status, queue, waitedMs, stale: lastError !== null, lastError, timedOut });
      if (finished || timedOut) return;

      const base = confirmed ? confirmedIntervalMs : (intervals[Math.min(round, intervals.length - 1)] ?? 2500);
      const delay = lastError?.kind === 'rate_limited' ? (lastError.retryAfterMs ?? 1000) : base;
      try {
        await sleep(delay, controller.signal);
      } catch {
        return;
      }
    }
  })();

  return () => controller.abort();
}

export interface SwitchWatchEvent {
  readonly progress: SwitchProgress | null;
  readonly stale: boolean;
  readonly lastError: ApiError | null;
}

/** 전환 요청 한 건을 COMPLETED/FAILED 까지 지켜본다. 전환은 보통 1초 안에 끝나므로 촘촘히 본다. */
export function watchSwitch(
  adminPolicy: AdminPolicyGateway,
  switchId: string,
  listener: (event: SwitchWatchEvent) => void,
  options: { intervalMs?: number; maxWaitMs?: number; now?: () => number } = {},
): () => void {
  const intervalMs = options.intervalMs ?? 250;
  const maxWaitMs = options.maxWaitMs ?? 60_000;
  const now = options.now ?? Date.now;
  const controller = new AbortController();
  const startedAt = now();

  void (async () => {
    let progress: SwitchProgress | null = null;
    while (!controller.signal.aborted) {
      let lastError: ApiError | null = null;
      try {
        progress = await adminPolicy.getSwitchProgress(switchId, { signal: controller.signal });
      } catch (e) {
        lastError = toApiError(e);
        if (lastError.kind === 'aborted') return;
      }
      if (controller.signal.aborted) return;
      listener({ progress, stale: lastError !== null, lastError });
      if (progress?.done || now() - startedAt >= maxWaitMs) return;
      try {
        await sleep(intervalMs, controller.signal);
      } catch {
        return;
      }
    }
  })();

  return () => controller.abort();
}
