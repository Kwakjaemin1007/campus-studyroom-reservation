import { describe, expect, it } from 'vitest';
import { createApi } from '@/api';
import { watchTicket } from '@/api/adapters/watchers';
import { toSwitchProgress, toSwitchId, toTicketView } from '@/api/adapters/mappers';
import { createHttpClient, parseRetryAfter } from '@/api/http/httpClient';
import { IdempotencyKeyRing } from '@/api/http/idempotency';
import { retryDelayMs, withRetry } from '@/api/http/retry';
import { StubSsoSession } from '@/auth/stubSsoSession';
import { ApiError, isApiError } from '@/domain/errors';
import type { TicketView } from '@/domain/reservation';

/** 백엔드 흉내. 요청을 기록하고 정해 둔 응답을 차례로 돌려준다. */
interface Recorded {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

type Reply = { status: number; body?: unknown; headers?: Record<string, string> } | 'network-error';

function fakeBackend(route: (req: Recorded, index: number) => Reply) {
  const calls: Recorded[] = [];
  const fetchImpl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const req: Recorded = {
      method: init?.method ?? 'GET',
      url: String(input),
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(req);
    const reply = route(req, calls.length - 1);
    if (reply === 'network-error') throw new TypeError('fetch failed');
    const text =
      reply.body === undefined ? '' : typeof reply.body === 'string' ? reply.body : JSON.stringify(reply.body);
    return new Response(reply.status === 204 ? null : text, { status: reply.status, headers: reply.headers });
  }) as typeof fetch;
  return { calls, fetchImpl };
}

function apiWith(route: (req: Recorded, index: number) => Reply, token = 'user:u-101:1') {
  const backend = fakeBackend(route);
  const session = new StubSsoSession(null);
  session.signIn(token);
  const api = createApi({ baseUrl: '/api', session, fetchImpl: backend.fetchImpl });
  return { api, calls: backend.calls };
}

const SLOT = { roomId: 'ENG-KC-1', startsAt: new Date('2026-11-20T05:00:00Z') };
const noSleep = async () => {};

describe('HTTP 클라이언트', () => {
  it('토큰을 Bearer 로 붙이고 기준 경로를 앞에 둔다', async () => {
    const { api, calls } = apiWith(() => ({ status: 200, body: { ticketId: 'R-1', status: 'PENDING', detail: null } }));
    await api.reservations.getTicket('R-1');
    expect(calls[0]?.url).toBe('/api/tickets/R-1');
    expect(calls[0]?.headers['Authorization']).toBe('Bearer user:u-101:1');
  });

  it('상태 코드를 오류 종류로 바꾸고 서버 문구를 보존한다', async () => {
    const http = createHttpClient({
      baseUrl: '/api',
      getToken: () => null,
      fetchImpl: fakeBackend(() => ({ status: 401, body: { error: 'missing token' } })).fetchImpl,
    });
    const err = await http.request({ method: 'GET', path: '/tickets/x' }).catch((e: unknown) => e);
    expect(isApiError(err) && err.kind).toBe('unauthorized');
    expect((err as ApiError).serverMessage).toBe('missing token');
  });

  it('Retry-After 를 초와 날짜 두 형식 모두 읽는다', () => {
    expect(parseRetryAfter('2')).toBe(2000);
    expect(parseRetryAfter('Fri, 20 Nov 2026 05:00:10 GMT', Date.parse('2026-11-20T05:00:00Z'))).toBe(10_000);
    expect(parseRetryAfter(null)).toBeNull();
  });

  it('연결 실패는 network 오류다', async () => {
    const http = createHttpClient({
      baseUrl: '/api',
      getToken: () => null,
      fetchImpl: fakeBackend(() => 'network-error').fetchImpl,
    });
    const err = await http.request({ method: 'GET', path: '/x' }).catch((e: unknown) => e);
    expect(isApiError(err) && err.kind).toBe('network');
  });
});

describe('재시도 규칙', () => {
  it('409 는 다시 보내지 않는다', async () => {
    let attempts = 0;
    const err = await withRetry(
      async () => {
        attempts++;
        throw new ApiError({ kind: 'conflict', status: 409 });
      },
      { sleep: noSleep },
    ).catch((e: unknown) => e);
    expect(attempts).toBe(1);
    expect(isApiError(err) && err.kind).toBe('conflict');
  });

  it('429 는 Retry-After 만큼 기다린다', () => {
    const delay = retryDelayMs(new ApiError({ kind: 'rate_limited', status: 429, retryAfterMs: 1000 }), 1);
    expect(delay).toBe(1000);
  });

  it('503 은 지수 백오프와 지터로 기다리고, 정한 횟수까지만 다시 보낸다', async () => {
    const e = new ApiError({ kind: 'unavailable', status: 503 });
    expect(retryDelayMs(e, 1, { random: () => 0.999 })).toBeLessThan(300);
    expect(retryDelayMs(e, 3, { random: () => 0.999 })).toBeGreaterThan(900);
    expect(retryDelayMs(e, 10, { random: () => 0.999 })).toBeLessThan(5000);

    let attempts = 0;
    await withRetry(
      async () => {
        attempts++;
        throw e;
      },
      { sleep: noSleep, maxAttempts: 4 },
    ).catch(() => undefined);
    expect(attempts).toBe(4);
  });
});

describe('예약 신청과 멱등키', () => {
  it('Idempotency-Key(UUID)를 붙이고 백엔드 본문 모양으로 보낸다', async () => {
    const { api, calls } = apiWith(() => ({ status: 202, body: { ticketId: 'R-1', status: 'PENDING', detail: null } }));
    const ticket = await api.reservations.reserve({ slot: SLOT, bidAmount: 20 });
    expect(ticket).toEqual<TicketView>({ ticketId: 'R-1', phase: 'QUEUED', reasonCode: null, settled: false });
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toBe('/api/reservations');
    expect(calls[0]?.body).toEqual({ roomId: 'ENG-KC-1', startsAt: '2026-11-20T05:00:00.000Z', bidAmount: 20 });
    expect(calls[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('연달아 두 번 눌러도 요청은 한 번만 나간다', async () => {
    const { api, calls } = apiWith(() => ({ status: 202, body: { ticketId: 'R-1', status: 'PENDING', detail: null } }));
    const [a, b] = await Promise.all([
      api.reservations.reserve({ slot: SLOT, bidAmount: 0 }),
      api.reservations.reserve({ slot: SLOT, bidAmount: 0 }),
    ]);
    expect(calls).toHaveLength(1);
    expect(a.ticketId).toBe(b.ticketId);
  });

  it('연결이 끊겨 다시 보낼 때 같은 키를 쓴다', async () => {
    const { api, calls } = apiWith((_req, i) =>
      i === 0 ? 'network-error' : { status: 202, body: { ticketId: 'R-1', status: 'PENDING', detail: null } },
    );
    await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    expect(calls).toHaveLength(2);
    expect(calls[1]?.headers['Idempotency-Key']).toBe(calls[0]?.headers['Idempotency-Key']);
  });

  it('대기 중인 신청을 다시 누르면 같은 키로 나가 같은 번호표를 받는다', async () => {
    const { api, calls } = apiWith(() => ({ status: 202, body: { ticketId: 'R-1', status: 'PENDING', detail: null } }));
    await api.reservations.reserve({ slot: SLOT, bidAmount: 20 });
    await api.reservations.reserve({ slot: SLOT, bidAmount: 35 });
    expect(calls[1]?.headers['Idempotency-Key']).toBe(calls[0]?.headers['Idempotency-Key']);
  });

  it('409 는 slot_closed 로 바꾸고, 다시 보내지 않으며, 다음 신청에는 새 키를 쓴다', async () => {
    const { api, calls } = apiWith((_req, i) =>
      i === 0
        ? { status: 409, body: { error: '이미 마감된 슬롯입니다' } }
        : { status: 202, body: { ticketId: 'R-2', status: 'PENDING', detail: null } },
    );
    const err = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 }).catch((e: unknown) => e);
    expect(isApiError(err) && err.kind).toBe('slot_closed');
    expect(calls).toHaveLength(1);
    await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    expect(calls[1]?.headers['Idempotency-Key']).not.toBe(calls[0]?.headers['Idempotency-Key']);
  });

  it('번호표가 거절로 끝나면 키를 버려 다음 신청이 새 요청이 된다', async () => {
    const replies: Reply[] = [
      { status: 202, body: { ticketId: 'R-1', status: 'PENDING', detail: null } },
      { status: 200, body: { ticketId: 'R-1', status: 'REJECTED', detail: 'OUTBID' } },
      { status: 202, body: { ticketId: 'R-2', status: 'PENDING', detail: null } },
    ];
    const { api, calls } = apiWith((_req, i) => replies[i] ?? { status: 500 });
    await api.reservations.reserve({ slot: SLOT, bidAmount: 20 });
    const settled = await api.reservations.getTicket('R-1');
    expect(settled.settled).toBe(true);
    await api.reservations.reserve({ slot: SLOT, bidAmount: 30 });
    expect(calls[2]?.headers['Idempotency-Key']).not.toBe(calls[0]?.headers['Idempotency-Key']);
  });

  it('키는 저장소에 남아 새로고침 뒤에도 이어진다', () => {
    const store = new Map<string, string>();
    const storage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    };
    const first = new IdempotencyKeyRing(storage).acquire('reserve|u-101|ENG-KC-1@x');
    expect(new IdempotencyKeyRing(storage).acquire('reserve|u-101|ENG-KC-1@x')).toBe(first);
  });

  it('취소의 409 는 not_cancelable 이다', async () => {
    const { api } = apiWith(() => ({ status: 409, body: { error: '취소할 수 없는 상태입니다' } }));
    const err = await api.reservations.cancel('R-1').catch((e: unknown) => e);
    expect(isApiError(err) && err.kind).toBe('not_cancelable');
  });
});

describe('관리자 정책 · QR 단말기', () => {
  it('전환 번호가 따옴표 없는 평문으로 와도 읽는다', async () => {
    const { api, calls } = apiWith(() => ({ status: 202, body: 'SW-1234' }), 'admin:a1');
    const { switchId } = await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    expect(switchId).toBe('SW-1234');
    expect(calls[0]?.body).toEqual({ target: 'CREDIT_BETTING' });
    expect(toSwitchId('"SW-9"')).toBe('SW-9');
  });

  it('전환의 409 는 switch_conflict 이고 다시 보내지 않는다', async () => {
    const { api, calls } = apiWith(() => ({ status: 409, body: { error: '다른 전환이 진행 중이다' } }), 'admin:a1');
    const err = await api.adminPolicy.requestSwitch('PRIORITY').catch((e: unknown) => e);
    expect(isApiError(err) && err.kind).toBe('switch_conflict');
    expect(calls).toHaveLength(1);
  });

  it('전환 상태를 전환 상태 기계의 위치로 옮긴다', () => {
    const EPOCH = Date.parse('2026-11-16T15:00:00Z') / 1000;
    const base = { switchId: 'SW-1', target: 'PRIORITY', fromVersion: 1, toVersion: 1, message: null, finishedAt: null };
    expect(toSwitchProgress({ ...base, state: 'REQUESTED', requestedAt: '2026-11-16T15:00:00Z' }).phase).toBe('SWITCH_REQUESTED');
    expect(toSwitchProgress({ ...base, state: 'DRAINING', requestedAt: EPOCH + 0.5 }).phase).toBe('DRAINING');
    const done = toSwitchProgress({ ...base, state: 'COMPLETED', toVersion: 2, requestedAt: EPOCH });
    expect(done.phase).toBe('ACTIVE');
    expect(done.done).toBe(true);
    expect(done.requestedAt?.toISOString()).toBe('2026-11-16T15:00:00.000Z');
  });

  it('QR 은 RSV: 형식으로 감싸 토큰 없이 보내고, 결과를 화면용 값으로 바꾼다', async () => {
    const { api, calls } = apiWith((req) =>
      (req.body as { payload: string }).payload === 'RSV:R-1'
        ? { status: 200, body: { checkedIn: true, result: 'APPLIED' } }
        : { status: 400, body: { error: 'unknown QR payload' } },
    );
    expect((await api.qrDevice.checkIn('R-1')).outcome).toBe('CHECKED_IN');
    expect(calls[0]?.headers['Authorization']).toBeUndefined();
    expect((await api.qrDevice.scanRaw('hello')).outcome).toBe('INVALID_QR');
  });

  it('번호표 상태 4가지를 화면 상태로 옮긴다', () => {
    expect(toTicketView({ ticketId: 'R', status: 'CONFIRMED', detail: null }).settled).toBe(false);
    expect(toTicketView({ ticketId: 'R', status: 'CANCELED', detail: 'NO_SHOW' })).toMatchObject({
      phase: 'CANCELED',
      reasonCode: 'NO_SHOW',
      settled: true,
    });
  });
});

describe('조회 API', () => {
  it('404 면 값을 지어내지 않고 unsupported 를 돌려준다', async () => {
    const { api } = apiWith(() => ({ status: 404, body: { error: 'Not Found' } }));
    expect((await api.credits.getBalance()).supported).toBe(false);
    expect((await api.queue.getLoad()).supported).toBe(false);
    expect((await api.slotBoard.getTaken({ roomIds: ['A'], from: new Date(0), to: new Date(1) })).supported).toBe(false);
    expect(await api.reservations.getDetail('R-1')).toBeNull();
    expect((await api.policyStatus.getStatus()).supported).toBe(false);
  });

  it('학생용 경로가 없으면 관리자는 관리자 API 로 현재 정책을 읽는다', async () => {
    const { api } = apiWith(
      (req) =>
        req.url.endsWith('/admin/policy')
          ? { status: 200, body: { name: 'FCFS', version: 1 } }
          : { status: 404, body: { error: 'Not Found' } },
      'admin:a1',
    );
    const status = await api.policyStatus.getStatus();
    expect(status.supported && status.value.policy).toEqual({ name: 'FCFS', version: 1 });
  });

  it('경로가 있으면 값이 들어온다', async () => {
    const { api } = apiWith(() => ({ status: 200, body: { balance: 80, held: 20, renewsAt: '2027-02-28T15:00:00Z' } }));
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value).toEqual({
      available: 80,
      held: 20,
      renewsAt: new Date('2027-02-28T15:00:00Z'),
    });
  });
});

describe('조회 API 응답 변환', () => {
  it('정책 상태는 판정 시점·전환 상태·타이머 길이를 함께 준다', async () => {
    const { api } = apiWith(() => ({
      status: 200,
      body: { name: 'CREDIT_BETTING', version: 3, timing: 'AT_CLOSE', phase: 'DRAINING', graceSeconds: 600, collectWindowSeconds: 300, maxSlotsPerDay: 1 },
    }));
    const status = await api.policyStatus.getStatus();
    expect(status.supported && status.value).toEqual({
      policy: { name: 'CREDIT_BETTING', version: 3 },
      timing: 'AT_CLOSE',
      phase: 'DRAINING',
      graceSeconds: 600,
      collectWindowSeconds: 300,
      maxSlotsPerDay: 1,
    });
  });

  it('차 있는 슬롯은 방 여러 개를 한 번에 물어 slotKey 집합으로 받는다', async () => {
    const { api, calls } = apiWith(() => ({
      status: 200,
      body: [
        { roomId: 'A', startsAt: '2026-11-18T05:00:00Z', taken: true },
        { roomId: 'B', startsAt: '2026-11-18T06:00:00Z', taken: true },
      ],
    }));
    const taken = await api.slotBoard.getTaken({
      roomIds: ['A', 'B'],
      from: new Date('2026-11-18T00:00:00Z'),
      to: new Date('2026-11-19T00:00:00Z'),
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toContain('roomId=A%2CB');
    expect(taken.supported && [...taken.value]).toEqual(['A@2026-11-18T05:00:00Z', 'B@2026-11-18T06:00:00Z']);
  });

  it('예약 상세는 상태 6가지와 입실 마감 시각을 그대로 옮긴다', async () => {
    const { api } = apiWith(() => ({
      status: 200,
      body: {
        id: 'R-1', status: 'CHECKED_IN', reason: null, roomId: 'A', startsAt: '2026-11-18T05:00:00Z',
        policyName: 'FCFS', policyVersion: 1, bidAmount: 0, chargedAmount: 0,
        requestedAt: '2026-11-17T01:00:00Z', updatedAt: '2026-11-18T05:03:00Z', checkInDeadline: '2026-11-18T05:10:00Z',
      },
    }));
    const detail = await api.reservations.getDetail('R-1');
    expect(detail?.status).toBe('CHECKED_IN');
    expect(detail?.checkInDeadline?.toISOString()).toBe('2026-11-18T05:10:00.000Z');
    expect(detail?.slot.roomId).toBe('A');
  });

  it('대기열은 순번이 아니라 전체 대기 수다', async () => {
    const { api } = apiWith(() => ({ status: 200, body: { depth: 42 } }));
    const load = await api.queue.getLoad();
    expect(load.supported && load.value).toEqual({ waiting: 42 });
  });
});

describe('번호표 지켜보기', () => {
  it('끝난 상태가 오면 멈추고, 없는 대기열 API 는 한 번만 물어본다', async () => {
    const tickets = ['PENDING', 'PENDING', 'REJECTED'];
    let ticketCalls = 0;
    let queueCalls = 0;
    const { api } = apiWith((req) => {
      if (req.url.endsWith('/queue')) {
        queueCalls++;
        return { status: 404, body: { error: 'Not Found' } };
      }
      if (req.url.includes('/reservations/')) return { status: 404, body: { error: '예약이 없습니다' } };
      const status = tickets[Math.min(ticketCalls++, tickets.length - 1)];
      return { status: 200, body: { ticketId: 'R-1', status, detail: status === 'REJECTED' ? 'LOST_FCFS' : null } };
    });

    const events = await new Promise<string[]>((resolve) => {
      const seen: string[] = [];
      watchTicket(
        api,
        'R-1',
        (e) => {
          seen.push(`${e.status}:${e.queue === null ? 'no-queue' : e.queue.waiting}`);
          if (e.ticket.settled) resolve(seen);
        },
        { intervalsMs: [1] },
      );
    });
    expect(events).toEqual(['QUEUED:no-queue', 'QUEUED:no-queue', 'REJECTED:no-queue']);
    expect(queueCalls).toBe(1);
  });

  it('확정 뒤에도 지켜보면 상세로 입실과 완료를 구분하고, 완료에서 멈춘다', async () => {
    const statuses = ['REQUESTED', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED'];
    let round = 0;
    const { api } = apiWith((req) => {
      const status = statuses[Math.min(round, statuses.length - 1)] ?? 'COMPLETED';
      if (req.url.endsWith('/queue')) return { status: 200, body: { depth: 7 } };
      if (req.url.includes('/reservations/')) {
        round++;
        return {
          status: 200,
          body: {
            id: 'R-1', status, reason: null, roomId: 'A', startsAt: '2026-11-18T05:00:00Z', policyName: 'PRIORITY',
            policyVersion: 2, bidAmount: 0, chargedAmount: 0, requestedAt: null, updatedAt: null, checkInDeadline: '2026-11-18T05:10:00Z',
          },
        };
      }
      return { status: 200, body: { ticketId: 'R-1', status: status === 'REQUESTED' ? 'PENDING' : 'CONFIRMED', detail: null } };
    });

    const seen = await new Promise<string[]>((resolve) => {
      const out: string[] = [];
      watchTicket(
        api,
        'R-1',
        (e) => {
          out.push(`${e.status}:${e.queue === null ? '-' : e.queue.waiting}`);
          if (e.status === 'COMPLETED') resolve(out);
        },
        { intervalsMs: [1], confirmedIntervalMs: 1, keepWatchingConfirmed: true },
      );
    });
    // 보류(REQUESTED)는 큐 대기가 아니므로 대기열을 묻지 않는다.
    expect(seen).toEqual(['REQUESTED:-', 'CONFIRMED:-', 'CHECKED_IN:-', 'COMPLETED:-']);
  });

  it('너무 오래 대기 상태면 timedOut 으로 알리고 멈춘다', async () => {
    const { api } = apiWith((req) =>
      req.url.endsWith('/queue') || req.url.includes('/reservations/')
        ? { status: 404 }
        : { status: 200, body: { ticketId: 'R-x', status: 'PENDING', detail: null } },
    );
    let clock = 0;
    const event = await new Promise<{ timedOut: boolean }>((resolve) => {
      watchTicket(api, 'R-x', (e) => e.timedOut && resolve(e), {
        intervalsMs: [1],
        maxWaitMs: 5000,
        now: () => (clock += 2000),
      });
    });
    expect(event.timedOut).toBe(true);
  });
});
