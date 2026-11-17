import { describe, expect, it } from 'vitest';
import { createApi } from '@/api';
import type { SessionPort } from '@/auth/sessionPort';
import type { Session } from '@/domain/session';
import { DemoBackend } from './demoServer';

/** 데모 서버를 진짜 어댑터 계층(createApi)에 끼워 백엔드 규칙대로 도는지 본다. */
function setup() {
  let clock = Date.parse('2026-11-17T00:00:00Z');
  const backend = new DemoBackend({ now: () => clock, queueDelayMs: 0, switchStepMs: 1000, collectWindowSeconds: 60 });
  let current: Session | null = null;
  const session: SessionPort = {
    current: () => current,
    signIn: () => {
      throw new Error('unused');
    },
    signOut: () => {
      current = null;
    },
    subscribe: () => () => undefined,
  };
  const api = createApi({ baseUrl: '/api', session, fetchImpl: backend.fetch });
  const as = (token: string) => {
    const [kind, id = '', prio = '0'] = token.split(':');
    current = { token, userId: id, role: kind === 'admin' ? 'admin' : 'student', priority: Number(prio) };
  };
  const tick = (ms: number) => (clock += ms);
  return { api, as, tick };
}

const SLOT = { roomId: 'ENG-KC-1', startsAt: new Date('2026-11-18T05:00:00Z') };

describe('데모 서버 (어댑터 계층에 끼워서)', () => {
  it('선착순: 첫 신청은 확정, 같은 칸의 다음 신청은 409', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    expect(t.phase).toBe('QUEUED');
    expect((await api.reservations.getTicket(t.ticketId)).phase).toBe('CONFIRMED');
    const taken = await api.slotBoard.getTaken({ roomIds: ['ENG-KC-1'], from: new Date('2026-11-18T00:00:00Z'), to: new Date('2026-11-19T00:00:00Z') });
    expect(taken.supported && taken.value.size).toBe(1);

    as('user:b:0');
    await expect(api.reservations.reserve({ slot: SLOT, bidAmount: 0 })).rejects.toMatchObject({ kind: 'slot_closed' });
  });

  it('QR 입실 뒤 상세는 CHECKED_IN, 다시 스캔하면 입실 불가', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    await api.reservations.getTicket(t.ticketId);
    expect((await api.qrDevice.checkIn(t.ticketId)).outcome).toBe('CHECKED_IN');
    expect((await api.reservations.getDetail(t.ticketId))?.status).toBe('CHECKED_IN');
    expect((await api.qrDevice.checkIn(t.ticketId)).outcome).toBe('NOT_ALLOWED');
    expect((await api.qrDevice.scanRaw('hello')).outcome).toBe('INVALID_QR');
  });

  it('정책 전환 후 베팅: 최고 입찰이 낙찰, 진 쪽은 환불, 접수 창이 열린 동안 전환은 409', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    const { switchId } = await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    const progress = await api.adminPolicy.getSwitchProgress(switchId);
    expect(progress.state).toBe('COMPLETED');
    expect(progress.toVersion).toBe(2);

    as('user:a:0');
    const a = await api.reservations.reserve({ slot: SLOT, bidAmount: 30 });
    as('user:b:0');
    const b = await api.reservations.reserve({ slot: SLOT, bidAmount: 50 });
    expect((await api.reservations.getDetail(b.ticketId))?.status).toBe('REQUESTED');
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value).toEqual({ available: 50, held: 50 });

    as('admin:root');
    await expect(api.adminPolicy.requestSwitch('FCFS')).rejects.toMatchObject({ kind: 'switch_conflict' });

    tick(61_000);
    as('user:a:0');
    const lost = await api.reservations.getTicket(a.ticketId);
    expect(lost).toMatchObject({ phase: 'REJECTED', reasonCode: 'OUTBID' });
    const back = await api.credits.getBalance();
    expect(back.supported && back.value).toEqual({ available: 100, held: 0 });
    as('user:b:0');
    expect((await api.reservations.getDetail(b.ticketId))?.chargedAmount).toBe(50);
  });

  it('우선순위: 마감 때 높은 우선순위가 이긴다', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.requestSwitch('PRIORITY');
    tick(2500);
    as('user:low:1');
    const low = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    as('user:high:3');
    const high = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    tick(61_000);
    expect((await api.reservations.getTicket(high.ticketId)).phase).toBe('CONFIRMED');
    as('user:low:1');
    expect(await api.reservations.getTicket(low.ticketId)).toMatchObject({ phase: 'REJECTED', reasonCode: 'LOWER_PRIORITY' });
  });

  it('노쇼: 시작 + 유예가 지나도 입실하지 않으면 해제되고 칸이 빈다', async () => {
    const { api, as, tick } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0 });
    await api.reservations.getTicket(t.ticketId);
    tick(Date.parse('2026-11-18T05:10:01Z') - Date.parse('2026-11-17T00:00:00Z'));
    expect(await api.reservations.getTicket(t.ticketId)).toMatchObject({ phase: 'CANCELED', reasonCode: 'NO_SHOW' });
  });

  it('같은 멱등키로 다시 보내면 같은 번호표, 남의 예약은 취소할 수 없다', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const [x, y] = await Promise.all([
      api.reservations.reserve({ slot: SLOT, bidAmount: 0 }),
      api.reservations.reserve({ slot: SLOT, bidAmount: 0 }),
    ]);
    expect(x.ticketId).toBe(y.ticketId);
    await api.reservations.getTicket(x.ticketId);
    as('user:b:0');
    await expect(api.reservations.cancel(x.ticketId)).rejects.toMatchObject({ kind: 'forbidden' });
  });
});
