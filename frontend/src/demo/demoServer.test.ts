import { describe, expect, it } from 'vitest';
import { createApi } from '@/api';
import type { SessionPort } from '@/auth/sessionPort';
import type { Session } from '@/domain/session';
import { creditPeriodOf, DemoBackend } from './demoServer';

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

/** 신청마다 새 동행 한 명(신청자 포함 최소 2명 규칙). 서로 겹치지 않게 매번 다른 학번 */
let mateSeq = 0;
const mate = () => [{ studentId: String(900000 + ++mateSeq), bid: 0 }];

describe('데모 서버 (어댑터 계층에 끼워서)', () => {
  it('선착순: 첫 신청은 확정, 같은 칸의 다음 신청은 409', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    expect(t.phase).toBe('QUEUED');
    expect((await api.reservations.getTicket(t.ticketId)).phase).toBe('CONFIRMED');
    const taken = await api.slotBoard.getTaken({ roomIds: ['ENG-KC-1'], from: new Date('2026-11-18T00:00:00Z'), to: new Date('2026-11-19T00:00:00Z') });
    expect(taken.supported && taken.value.size).toBe(1);

    as('user:b:0');
    await expect(api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() })).rejects.toMatchObject({ kind: 'slot_closed' });
  });

  it('QR 입실 뒤 상세는 CHECKED_IN, 다시 스캔하면 입실 불가', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
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
    const a = await api.reservations.reserve({ slot: SLOT, bidAmount: 30, companions: mate() });
    as('user:b:0');
    const b = await api.reservations.reserve({ slot: SLOT, bidAmount: 50, companions: mate() });
    expect((await api.reservations.getDetail(b.ticketId))?.status).toBe('REQUESTED');
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value).toMatchObject({ available: 50, held: 50 });

    as('admin:root');
    await expect(api.adminPolicy.requestSwitch('FCFS')).rejects.toMatchObject({ kind: 'switch_conflict' });

    tick(61_000);
    as('user:a:0');
    const lost = await api.reservations.getTicket(a.ticketId);
    expect(lost).toMatchObject({ phase: 'REJECTED', reasonCode: 'OUTBID' });
    const back = await api.credits.getBalance();
    expect(back.supported && back.value).toMatchObject({ available: 100, held: 0 });
    as('user:b:0');
    expect((await api.reservations.getDetail(b.ticketId))?.chargedAmount).toBe(50);
  });

  it('우선순위: 마감 때 높은 우선순위가 이긴다', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.requestSwitch('PRIORITY');
    tick(2500);
    as('user:low:1');
    const low = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    as('user:high:3');
    const high = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    tick(61_000);
    expect((await api.reservations.getTicket(high.ticketId)).phase).toBe('CONFIRMED');
    as('user:low:1');
    expect(await api.reservations.getTicket(low.ticketId)).toMatchObject({ phase: 'REJECTED', reasonCode: 'LOWER_PRIORITY' });
  });

  it('노쇼: 시작 + 유예가 지나도 입실하지 않으면 해제되고 칸이 빈다', async () => {
    const { api, as, tick } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    await api.reservations.getTicket(t.ticketId);
    tick(Date.parse('2026-11-18T05:10:01Z') - Date.parse('2026-11-17T00:00:00Z'));
    expect(await api.reservations.getTicket(t.ticketId)).toMatchObject({ phase: 'CANCELED', reasonCode: 'NO_SHOW' });
  });

  it('같은 멱등키로 다시 보내면 같은 번호표, 남의 예약은 취소할 수 없다', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const [x, y] = await Promise.all([
      api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() }),
      api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() }),
    ]);
    expect(x.ticketId).toBe(y.ticketId);
    await api.reservations.getTicket(x.ticketId);
    as('user:b:0');
    await expect(api.reservations.cancel(x.ticketId)).rejects.toMatchObject({ kind: 'forbidden' });
  });
});

describe('하루 예약 한도', () => {
  const day = (hour: number, room = 'ENG-KC-1') => ({ roomId: room, startsAt: new Date(`2026-11-18T${String(hour).padStart(2, '0')}:00:00Z`) });

  it('선착순: 하루 1시간까지, 두 번째는 DAILY_LIMIT 로 거절, 다음 날은 새 한도', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const policy = await api.policyStatus.getStatus();
    expect(policy.supported && policy.value.maxSlotsPerDay).toBe(1);
    const t1 = await api.reservations.reserve({ slot: day(1, 'R-1'), bidAmount: 0, companions: mate() });
    const t2 = await api.reservations.reserve({ slot: day(2, 'R-2'), bidAmount: 0, companions: mate() });
    expect((await api.reservations.getTicket(t1.ticketId)).phase).toBe('CONFIRMED');
    const rejected = await api.reservations.getTicket(t2.ticketId);
    expect(rejected.phase).toBe('REJECTED');
    expect(rejected.reasonCode).toBe('DAILY_LIMIT');
    // 한국 시각 기준 다음 날(2026-11-19 10:00 KST)
    const next = await api.reservations.reserve({ slot: { roomId: 'R-1', startsAt: new Date('2026-11-19T01:00:00Z') }, bidAmount: 0, companions: mate() });
    expect((await api.reservations.getTicket(next.ticketId)).phase).toBe('CONFIRMED');
  });

  it('직접 취소하면 한도가 돌아온다', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const t1 = await api.reservations.reserve({ slot: day(1, 'R-1'), bidAmount: 0, companions: mate() });
    await api.reservations.cancel(t1.ticketId);
    const again = await api.reservations.reserve({ slot: day(3, 'R-3'), bidAmount: 0, companions: mate() });
    expect((await api.reservations.getTicket(again.ticketId)).phase).toBe('CONFIRMED');
  });

  it('크레딧 베팅과 우선순위는 하루 한도가 없다', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    as('user:a:0');
    const status = await api.policyStatus.getStatus();
    expect(status.supported && status.value.maxSlotsPerDay).toBeNull();
    const first = await api.reservations.reserve({ slot: day(5, 'R-1'), bidAmount: 30, companions: mate() });
    const second = await api.reservations.reserve({ slot: day(6, 'R-2'), bidAmount: 20, companions: mate() });
    expect((await api.reservations.getDetail(first.ticketId))?.status).toBe('REQUESTED');
    expect((await api.reservations.getDetail(second.ticketId))?.status).toBe('REQUESTED');
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value).toMatchObject({ available: 50, held: 50 });
  });
});

describe('크레딧 학기별 갱신', () => {
  it('학기 경계는 한국 시각 3/1, 9/1 0시', () => {
    expect(creditPeriodOf(Date.parse('2026-02-28T14:59:59Z')).id).toBe('2025-2');
    expect(creditPeriodOf(Date.parse('2026-02-28T15:00:00Z')).id).toBe('2026-1'); // 한국 3/1 00:00
    expect(creditPeriodOf(Date.parse('2026-08-31T14:59:59Z')).id).toBe('2026-1');
    expect(creditPeriodOf(Date.parse('2026-08-31T15:00:00Z')).id).toBe('2026-2'); // 한국 9/1 00:00
    expect(new Date(creditPeriodOf(Date.parse('2026-11-17T00:00:00Z')).renewsAtMs).toISOString()).toBe('2027-02-28T15:00:00.000Z');
  });

  it('학기 중에는 다시 채워지지 않고, 새 학기에 지급량으로 초기화된다(이월 없음)', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    as('user:a:0');
    const slot = { roomId: 'R-1', startsAt: new Date('2026-11-18T05:00:00Z') };
    const t = await api.reservations.reserve({ slot, bidAmount: 60, companions: mate() });
    tick(61_000);
    expect((await api.reservations.getTicket(t.ticketId)).phase).toBe('CONFIRMED');
    const spent = await api.credits.getBalance();
    expect(spent.supported && spent.value.available).toBe(40);
    expect(spent.supported && spent.value.renewsAt?.toISOString()).toBe('2027-02-28T15:00:00.000Z');

    tick(60 * 86_400_000); // 2027-01 중순, 같은 학기
    const same = await api.credits.getBalance();
    expect(same.supported && same.value.available).toBe(40);

    tick(60 * 86_400_000); // 2027-03 중순, 새 학기
    const renewed = await api.credits.getBalance();
    expect(renewed.supported && renewed.value.available).toBe(100);
  });
});

describe('데모 서버: 함께 신청(11/19 회의 규칙)', () => {
  const at = (h: number, room: string) => ({ roomId: room, startsAt: new Date(Date.UTC(2026, 10, 18, h)) });

  it('혼자 신청하면 400', async () => {
    const { api, as } = setup();
    as('user:1000');
    await expect(api.reservations.reserve({ slot: at(1, 'G-1'), bidAmount: 0, companions: [] })).rejects.toMatchObject({
      kind: 'validation',
    });
  });

  it('등록된 학번은 같은 시간에 다른 예약에 못 들어간다', async () => {
    const { api, as } = setup();
    as('user:1000');
    const first = await api.reservations.reserve({ slot: at(1, 'G-1'), bidAmount: 0, companions: [{ studentId: '2000', bid: 0 }] });
    as('user:3000');
    const clash = await api.reservations.reserve({ slot: at(1, 'G-2'), bidAmount: 0, companions: [{ studentId: '2000', bid: 0 }] });
    as('user:1000');
    expect((await api.reservations.getTicket(first.ticketId)).phase).toBe('CONFIRMED');
    as('user:3000');
    const t = await api.reservations.getTicket(clash.ticketId);
    expect(t.phase).toBe('REJECTED');
    expect(t.reasonCode).toBe('MEMBER_TIME_CONFLICT');
  });

  it('크레딧 베팅은 구성원 크레딧의 합으로 겨루고 각자 자기 몫만 낸다', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    const { switchId } = await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    expect((await api.adminPolicy.getSwitchProgress(switchId)).state).toBe('COMPLETED');
    as('user:1000');
    const team = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 30, companions: [{ studentId: '2000', bid: 30 }] });
    as('user:3000');
    const rival = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 50, companions: [{ studentId: '4000', bid: 0 }] });
    tick(61_000);
    as('user:1000');
    expect((await api.reservations.getTicket(team.ticketId)).phase).toBe('CONFIRMED');
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value.available).toBe(70);
    as('user:2000');
    const mateCredits = await api.credits.getBalance();
    expect(mateCredits.supported && mateCredits.value.available).toBe(70);
    as('user:3000');
    expect((await api.reservations.getTicket(rival.ticketId)).reasonCode).toBe('OUTBID');
  });
});
