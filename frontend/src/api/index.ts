import { createAdminPolicyAdapter } from './adapters/adminPolicyAdapter';
import {
  createCreditAdapter,
  createPolicyStatusAdapter,
  createQueueAdapter,
  createSlotBoardAdapter,
} from './adapters/queryAdapters';
import { createQrDeviceAdapter } from './adapters/qrDeviceAdapter';
import { createReservationAdapter } from './adapters/reservationAdapter';
import { createHttpClient } from './http/httpClient';
import { browserSessionStorage, IdempotencyKeyRing, SingleFlight } from './http/idempotency';
import type { Api } from './ports';
import type { SessionPort } from '@/auth/sessionPort';

/**
 * 조립 루트. 백엔드의 StudyRoomSystem 처럼, 구현을 고르고 서로 연결하는 일은 여기서만 한다.
 * 화면은 createApi 가 돌려준 Api(포트 묶음)만 받는다.
 */
export interface ApiConfig {
  readonly baseUrl: string;
  readonly session: SessionPort;
  readonly timeoutMs?: number;
  readonly fetchImpl?: typeof fetch;
}

export function createApi(config: ApiConfig): Api {
  const http = createHttpClient({
    baseUrl: config.baseUrl,
    getToken: () => config.session.current()?.token ?? null,
    timeoutMs: config.timeoutMs,
    fetchImpl: config.fetchImpl,
  });

  const adminPolicy = createAdminPolicyAdapter(http);

  return {
    reservations: createReservationAdapter({
      http,
      keys: new IdempotencyKeyRing(browserSessionStorage()),
      flights: new SingleFlight(),
      currentUserId: () => config.session.current()?.userId ?? null,
    }),
    adminPolicy,
    qrDevice: createQrDeviceAdapter(http),
    policyStatus: createPolicyStatusAdapter({
      http,
      adminPolicy,
      isAdmin: () => config.session.current()?.role === 'admin',
    }),
    slotBoard: createSlotBoardAdapter(http),
    credits: createCreditAdapter(http),
    queue: createQueueAdapter(http),
  };
}

export type { Api } from './ports';
export * from './ports';
export { watchTicket, watchSwitch, FAST_INTERVALS, SLOW_INTERVALS } from './adapters/watchers';
export type { TicketWatchEvent, SwitchWatchEvent } from './adapters/watchers';
