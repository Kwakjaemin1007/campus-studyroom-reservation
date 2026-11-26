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
const mate = () => [{ studentId: String(900000 + ++mateSeq) }];

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
    const { api, as, tick } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    await api.reservations.getTicket(t.ticketId);
    tick(SLOT.startsAt.getTime() - Date.parse('2026-11-17T00:00:00Z')); // 시작 시각으로
    expect((await api.qrDevice.checkIn(t.ticketId)).outcome).toBe('CHECKED_IN');
    expect((await api.reservations.getDetail(t.ticketId))?.status).toBe('CHECKED_IN');
    expect((await api.qrDevice.checkIn(t.ticketId)).outcome).toBe('NOT_ALLOWED');
    expect((await api.qrDevice.scanRaw('hello')).outcome).toBe('INVALID_QR');
  });

  it('입실 시간·호실 확인: 이르면 TOO_EARLY, 다른 방이면 WRONG_ROOM, 유예가 끝나면 노쇼 해제로 입실 불가', async () => {
    const { api, as, tick } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    await api.reservations.getTicket(t.ticketId);
    const toStart = SLOT.startsAt.getTime() - Date.parse('2026-11-17T00:00:00Z');
    tick(toStart - 11 * 60_000); // 시작 11분 전
    expect((await api.qrDevice.checkIn(t.ticketId)).outcome).toBe('TOO_EARLY');
    tick(60_000); // 시작 10분 전부터 된다. 그래도 다른 방이면 안 된다
    expect((await api.qrDevice.checkIn(t.ticketId, { roomId: 'ENG-KC-2' })).outcome).toBe('WRONG_ROOM');
    tick(20 * 60_000); // 시작 10분 뒤 = 유예 끝
    expect((await api.qrDevice.checkIn(t.ticketId, { roomId: 'ENG-KC-1' })).outcome).toBe('NOT_ALLOWED'); // 이미 노쇼 해제
  });

  it('입실한 예약은 슬롯이 끝나면 COMPLETED', async () => {
    const { api, as, tick } = setup();
    as('user:a:0');
    const t = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    await api.reservations.getTicket(t.ticketId);
    tick(SLOT.startsAt.getTime() - Date.parse('2026-11-17T00:00:00Z'));
    expect((await api.qrDevice.checkIn(t.ticketId, { roomId: 'ENG-KC-1' })).outcome).toBe('CHECKED_IN');
    tick(60 * 60_000);
    expect((await api.reservations.getDetail(t.ticketId))?.status).toBe('COMPLETED');
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
    // 낙찰가는 팀 입찰액(50 × 2명). 각자 50씩 같은 금액을 낸다
    expect((await api.reservations.getDetail(b.ticketId))?.chargedAmount).toBe(100);
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

  it('크레딧 베팅은 하루 2시간까지(11/25 결정)', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    as('user:a:0');
    const status = await api.policyStatus.getStatus();
    expect(status.supported && status.value.maxSlotsPerDay).toBe(2);
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
    const first = await api.reservations.reserve({ slot: at(1, 'G-1'), bidAmount: 0, companions: [{ studentId: '2000' }] });
    as('user:3000');
    const clash = await api.reservations.reserve({ slot: at(1, 'G-2'), bidAmount: 0, companions: [{ studentId: '2000' }] });
    as('user:1000');
    expect((await api.reservations.getTicket(first.ticketId)).phase).toBe('CONFIRMED');
    as('user:3000');
    const t = await api.reservations.getTicket(clash.ticketId);
    expect(t.phase).toBe('REJECTED');
    expect(t.reasonCode).toBe('MEMBER_TIME_CONFLICT');
  });

  async function toCreditBetting(api: ReturnType<typeof setup>['api'], as: (t: string) => void, tick: (ms: number) => void) {
    as('admin:root');
    const { switchId } = await api.adminPolicy.requestSwitch('CREDIT_BETTING');
    tick(2500);
    expect((await api.adminPolicy.getSwitchProgress(switchId)).state).toBe('COMPLETED');
  }

  it('재학생이 아닌 학번이 있으면 400 NOT_ENROLLED:학번', async () => {
    const { api, as } = setup();
    as('user:1000');
    await expect(
      api.reservations.reserve({ slot: at(1, 'G-1'), bidAmount: 0, companions: [{ studentId: '000777' }] }),
    ).rejects.toMatchObject({ kind: 'validation', serverMessage: 'NOT_ENROLLED:000777' });
  });

  it('크레딧 베팅: 전원이 1인당 금액을 똑같이 걸고, 팀 입찰액은 1인당 × 인원', async () => {
    const { api, as, tick } = setup();
    await toCreditBetting(api, as, tick);
    as('user:1000');
    const team = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 30, companions: [{ studentId: '2000' }] });
    as('user:3000');
    const rival = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 25, companions: [{ studentId: '4000' }] });
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

  it('합계가 같으면 인원이 많은 팀', async () => {
    const { api, as, tick } = setup();
    await toCreditBetting(api, as, tick);
    as('user:1000');
    const pair = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 30, companions: [{ studentId: '2000' }] });
    as('user:3000');
    const trio = await api.reservations.reserve({
      slot: at(9, 'G-1'),
      bidAmount: 20,
      companions: [{ studentId: '4000' }, { studentId: '5000' }],
    });
    tick(61_000);
    as('user:3000');
    expect((await api.reservations.getTicket(trio.ticketId)).phase).toBe('CONFIRMED');
    as('user:1000');
    expect((await api.reservations.getTicket(pair.ticketId)).phase).toBe('REJECTED');
  });

  it('합계가 같으면 이번 학기 노쇼가 적은 팀', async () => {
    const { api, as, tick } = setup();
    await toCreditBetting(api, as, tick);
    as('user:1000'); // 1000과 2000이 노쇼를 낸다
    const noShow = await api.reservations.reserve({ slot: at(1, 'G-1'), bidAmount: 10, companions: [{ studentId: '2000' }] });
    tick(61_000);
    expect((await api.reservations.getTicket(noShow.ticketId)).phase).toBe('CONFIRMED');
    tick(Date.UTC(2026, 10, 18, 1, 10, 1) - Date.UTC(2026, 10, 17, 0, 1, 3) + 1000);
    expect((await api.reservations.getTicket(noShow.ticketId)).reasonCode).toBe('NO_SHOW');

    const penalized = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 30, companions: [{ studentId: '6000' }] });
    as('user:3000');
    const clean = await api.reservations.reserve({ slot: at(9, 'G-1'), bidAmount: 30, companions: [{ studentId: '4000' }] });
    tick(61_000);
    expect((await api.reservations.getTicket(clean.ticketId)).phase).toBe('CONFIRMED');
    as('user:1000');
    expect((await api.reservations.getTicket(penalized.ticketId)).phase).toBe('REJECTED');
  });
});

describe('시험 기간 (11/25 결정, 백엔드 ExamPeriodTest 와 같은 규칙)', () => {
  // setup 의 시계: 2026-11-17 09:00 KST
  const at = (dateKey: string, hour: number, room = 'ENG-KC-1') => ({
    roomId: room,
    startsAt: new Date(`${dateKey}T${String(hour).padStart(2, '0')}:00:00+09:00`),
  });

  it('등록하면 그 날짜 자리는 베팅, 미리 잡힌 자리는 취소, 나머지 날짜는 선착순', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const early = await api.reservations.reserve({ slot: at('2026-11-21', 14), bidAmount: 0, companions: mate() });
    expect((await api.reservations.getTicket(early.ticketId)).phase).toBe('CONFIRMED');

    as('admin:root');
    const reg = await api.adminPolicy.registerExamPeriod({ start: '2026-11-20', end: '2026-11-22', policy: 'CREDIT_BETTING' });
    expect(reg.canceled).toBe(1);
    expect((await api.adminPolicy.listExamPeriods()).map((p) => p.start)).toEqual(['2026-11-20']);

    as('user:b:0');
    const status = await api.policyStatus.getStatus();
    expect(status.supported && status.value.examPeriods[0]).toMatchObject({ policy: 'CREDIT_BETTING', timing: 'AT_CLOSE', maxSlotsPerDay: 2 });
    const tooEarly = await api.reservations
      .reserve({ slot: at('2026-11-22', 15), bidAmount: 10, companions: mate() })
      .catch((e: unknown) => e);
    expect(tooEarly).toMatchObject({ kind: 'booking_rule' });
    expect((tooEarly as { userMessage: string }).userMessage).toContain('11/21부터');

    const outside = await api.reservations.reserve({ slot: at('2026-11-23', 15), bidAmount: 10, companions: mate() });
    expect((await api.reservations.getDetail(outside.ticketId))?.status).toBe('CONFIRMED');
  });

  it('운영 시간 밖 자리는 받지 않는다', async () => {
    const { api, as } = setup();
    as('user:a:0');
    const e = await api.reservations.reserve({ slot: at('2026-11-18', 22), bidAmount: 0, companions: mate() }).catch((x: unknown) => x);
    expect(e).toMatchObject({ kind: 'booking_rule' });
  });

  it('7일 뒤 날짜까지만 받고, 시작한 시간은 끝나기 20분 전까지만 받는다 (11/26 결정)', async () => {
    const { api, as, tick } = setup();
    as('user:a:0');
    const ok = await api.reservations.reserve({ slot: at('2026-11-24', 21), bidAmount: 0, companions: mate() });
    expect((await api.reservations.getDetail(ok.ticketId))?.status).toBe('CONFIRMED');
    const far = await api.reservations.reserve({ slot: at('2026-11-25', 9), bidAmount: 0, companions: mate() }).catch((x: unknown) => x);
    expect(far).toMatchObject({ kind: 'booking_rule' });
    expect((far as { userMessage: string }).userMessage).toContain('11/24');

    tick(Date.parse('2026-11-18T05:40:00Z') - Date.parse('2026-11-17T00:00:00Z'));
    const late = await api.reservations.reserve({ slot: SLOT, bidAmount: 0, companions: mate() });
    expect((await api.reservations.getDetail(late.ticketId))?.checkInDeadline?.toISOString()).toBe('2026-11-18T05:50:00.000Z');
    tick(60_000);
    const ending = await api.reservations.reserve({ slot: { ...SLOT, roomId: 'ENG-KC-2' }, bidAmount: 0, companions: mate() }).catch((x: unknown) => x);
    expect(ending).toMatchObject({ kind: 'booking_rule' });
  });

  it('한 사람은 시작 전 예약을 3개까지만 들고 있다 (11/26 결정)', async () => {
    const { api, as } = setup();
    as('user:a:0');
    for (const d of ['2026-11-18', '2026-11-19', '2026-11-20']) {
      const t = await api.reservations.reserve({ slot: at(d, 10), bidAmount: 0, companions: mate() });
      expect((await api.reservations.getTicket(t.ticketId)).phase).toBe('CONFIRMED');
    }
    const fourth = await api.reservations.reserve({ slot: at('2026-11-21', 10), bidAmount: 0, companions: mate() });
    expect(await api.reservations.getTicket(fourth.ticketId)).toMatchObject({ phase: 'REJECTED', reasonCode: 'PENDING_LIMIT' });
  });

  it('시험 기간이라도 이미 시작한 자리(노쇼로 풀린 자리)는 선착순 (11/26 결정)', async () => {
    const { api, as, tick } = setup();
    as('admin:root');
    await api.adminPolicy.registerExamPeriod({ start: '2026-11-18', end: '2026-11-20', policy: 'CREDIT_BETTING' });
    tick(Date.parse('2026-11-18T05:15:00Z') - Date.parse('2026-11-17T00:00:00Z'));   // 11/18 14:15 KST
    as('user:a:0');
    const late = await api.reservations.reserve({ slot: SLOT, bidAmount: 30, companions: mate() });
    const detail = await api.reservations.getDetail(late.ticketId);
    expect(detail).toMatchObject({ status: 'CONFIRMED', policyName: 'FCFS', chargedAmount: 0 });
    const credits = await api.credits.getBalance();
    expect(credits.supported && credits.value).toMatchObject({ available: 100, held: 0 });
  });
});
