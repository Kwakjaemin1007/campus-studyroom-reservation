/**
 * 데모 서버: 백엔드 app 모듈의 HTTP 계약을 브라우저 안에서 흉내 내는 fetch 구현.
 *
 * 쓰는 곳: Java 서버 없이 링크만으로 여는 데모 빌드(VITE_DEMO=1). services.ts 가 createApi 의
 * fetchImpl 자리에 끼운다. 화면·어댑터 코드는 진짜 서버에 붙을 때와 똑같다(Adapter 교체).
 *
 * 옮겨 온 규칙(core 모듈 기준):
 *  - 접수(ReservationIntake): 멱등키 → 같은 요청 id, 마감 슬롯 조기 거절 409, 큐 적재 후 202 + PENDING
 *  - 배정(AllocationProcessor): 즉시형(FCFS)은 큐에서 꺼낼 때 판정, 마감형(PRIORITY·CREDIT_BETTING)은
 *    보류(REQUESTED) 후 "첫 요청 + 접수 창" 시각의 SLOT_CLOSE 지연 메시지로 슬롯별 일괄 판정
 *  - 정책 3종의 판정 규칙과 사유 코드, 크레딧 보류·차감·환불(CreditLedger, FIRST_PRICE)
 *  - 상태 전이(ReservationState): 허용되지 않는 사건은 무시. 노쇼는 시작 + 유예 시각의 지연 메시지
 *  - 정책 전환(PolicySwitchManager): 같은 정책·접수 창 열림·다른 전환 진행 중이면 409,
 *    REQUESTED → DRAINING(배정 멈춤) → COMPLETED, 버전 + 1
 *  - 함께 신청(11/19 회의, PartyGuard): 신청자 포함 최소 2명, 등록된 학번은 같은 시간에 다른 예약 불가
 *    (MEMBER_TIME_CONFLICT), 하루 한도는 사람마다, 크레딧 베팅 우선순위는 구성원 크레딧의 합
 *    (각자 자기 몫을 보류, 한 명이라도 모자라면 전원 보류 안 함)
 *
 * 다른 점: 스레드·Redis·SQS 대신 "시각이 된 일은 다음 요청 때 처리"한다. 상태는 이 브라우저의
 * localStorage 에만 있다(같은 브라우저의 다른 탭과는 공유된다). 동시성·장애 대응은 흉내 내지 않는다.
 */

export type Status = 'REQUESTED' | 'CONFIRMED' | 'REJECTED' | 'CHECKED_IN' | 'CANCELED' | 'COMPLETED';
type PolicyName = 'FCFS' | 'PRIORITY' | 'CREDIT_BETTING';
type Phase = 'ACTIVE' | 'SWITCH_REQUESTED' | 'DRAINING';

/** 한 사람이 하루(한국 시각)에 잡을 수 있는 슬롯 수. 백엔드 IAllocationPolicy.maxSlotsPerDay() 와 같은 값. null 은 제한 없음 */
const MAX_SLOTS_PER_DAY: Readonly<Record<string, number | null>> = {
  FCFS: 1,
  CREDIT_BETTING: null,
  PRIORITY: null,
};

/**
 * 크레딧 갱신 기간(학기). 백엔드 SemesterCreditPeriod 와 같다: 1학기 3/1, 2학기 9/1 0시(한국 시각)에 시작.
 * id 는 "2026-2" 꼴이고, renewsAt 은 다음 학기가 시작하는 시각(UTC ms)이다.
 */
export function creditPeriodOf(nowMs: number): { id: string; renewsAtMs: number } {
  const t = new Date(nowMs + KST_OFFSET_MS); // UTC 필드를 한국 시각처럼 읽는다
  const year = t.getUTCFullYear();
  const month = t.getUTCMonth() + 1;
  const kstMidnightMs = (y: number, m: number) => Date.UTC(y, m - 1, 1) - KST_OFFSET_MS;
  if (month >= 3 && month <= 8) return { id: `${year}-1`, renewsAtMs: kstMidnightMs(year, 9) };
  if (month >= 9) return { id: `${year}-2`, renewsAtMs: kstMidnightMs(year + 1, 3) };
  return { id: `${year - 1}-2`, renewsAtMs: kstMidnightMs(year, 3) };
}

const KST_OFFSET_MS = 9 * 3_600_000;
function kstDay(iso: string): number {
  return Math.floor((Date.parse(iso) + KST_OFFSET_MS) / 86_400_000);
}

const POLICIES: Readonly<Record<PolicyName, 'IMMEDIATE' | 'AT_CLOSE'>> = {
  FCFS: 'IMMEDIATE',
  PRIORITY: 'AT_CLOSE',
  CREDIT_BETTING: 'AT_CLOSE',
};

/** 신청자 + 동행. 첫 번째가 신청자 */
interface Member {
  userId: string;
  bid: number;
}

export interface DemoOptions {
  /** 노쇼 유예(초). 백엔드 기본값 600 */
  readonly graceSeconds: number;
  /** 마감형 정책의 접수 창(초). 백엔드 기본값은 600 이지만 데모에서는 짧게 둔다 */
  readonly collectWindowSeconds: number;
  /** 학기당 지급 크레딧. 백엔드 기본값 100 */
  readonly initialCredits: number;
  /** 신청자 포함 최소 인원. 백엔드 기본값 2 */
  readonly minPartySize: number;
  /** 큐에서 처리되기까지의 지연(ms). 접수 대기 화면을 보이게 한다 */
  readonly queueDelayMs: number;
  /** 전환 단계 사이 간격(ms) */
  readonly switchStepMs: number;
  readonly storage: Storage | null;
  readonly storageKey: string;
  readonly now: () => number;
}

export const DEFAULT_DEMO_OPTIONS: Omit<DemoOptions, 'storage' | 'now'> = {
  graceSeconds: 600,
  collectWindowSeconds: 60,
  initialCredits: 100,
  minPartySize: 2,
  queueDelayMs: 600,
  switchStepMs: 1200,
  // v2: 함께 신청(구성원·구성원별 크레딧 보류)으로 저장 형식이 바뀌어 옛 데모 데이터는 버린다
  storageKey: 'studyroom.demo.v2',
};

interface Reservation {
  id: string;
  userId: string;
  priority: number;
  roomId: string;
  startsAt: string;
  status: Status;
  reason: string | null;
  policyName: string;
  policyVersion: number;
  bidAmount: number;
  chargedAmount: number;
  sequence: number;
  requestedAt: number;
  updatedAt: number;
  party: Member[];
}

interface QueuedRequest {
  id: string;
  userId: string;
  priority: number;
  roomId: string;
  startsAt: string;
  bidAmount: number;
  sequence: number;
  requestedAt: number;
  readyAt: number;
  party: Member[];
}

interface Delayed {
  key: string;
  type: 'NO_SHOW' | 'SLOT_CLOSE';
  subject: string;
  dueAt: number;
}

interface SwitchRecord {
  switchId: string;
  target: PolicyName;
  state: 'REQUESTED' | 'DRAINING' | 'COMPLETED' | 'FAILED';
  fromVersion: number;
  toVersion: number;
  message: string | null;
  requestedAt: number;
  finishedAt: number | null;
}

interface State {
  v: 1;
  policy: { name: PolicyName; version: number };
  sequence: number;
  reservations: Record<string, Reservation>;
  queue: QueuedRequest[];
  delayed: Delayed[];
  balances: Record<string, number>;
  /** 사용자별로 마지막으로 잔액을 맞춘 갱신 기간. 기간이 바뀌면 지급량으로 초기화한다 */
  balancePeriods: Record<string, string>;
  /** 키: `${예약 id}|${학번}`. 구성원마다 자기 몫을 보류한다 */
  holds: Record<string, { reservationId: string; userId: string; amount: number; period: string }>;
  switches: Record<string, SwitchRecord>;
  activeSwitch: string | null;
}

interface Identity {
  userId: string;
  priority: number;
  admin: boolean;
}

function emptyState(): State {
  return {
    v: 1,
    policy: { name: 'FCFS', version: 1 },
    sequence: 0,
    reservations: {},
    queue: [],
    delayed: [],
    balances: {},
    balancePeriods: {},
    holds: {},
    switches: {},
    activeSwitch: null,
  };
}

/** 요청 id = R-{userId 와 멱등키의 해시}. 백엔드 Ids.requestId 처럼 같은 키는 같은 id 가 된다. */
export function requestId(userId: string, idempotencyKey: string): string {
  const text = `${userId}\n${idempotencyKey}`;
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  let h3 = 0x9e3779b9;
  let h4 = 0x85ebca6b;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193);
    h2 = Math.imul(h2 ^ c, 0x5bd1e995);
    h3 = Math.imul(h3 + c, 0x27d4eb2d);
    h4 = Math.imul(h4 ^ (c << 3), 0x165667b1);
  }
  const hex = [h1, h2, h3, h4].map((n) => (n >>> 0).toString(16).padStart(8, '0')).join('');
  return `R-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

function slotKey(roomId: string, startsAt: string): string {
  return `${roomId}@${startsAt}`;
}

/** 백엔드 Instant 직렬화처럼 밀리초 없는 ISO 문자열로 맞춘다. */
function canonicalInstant(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const t = Date.parse(raw);
  if (Number.isNaN(t)) return null;
  return new Date(t).toISOString().replace('.000Z', 'Z');
}

function iso(ms: number | null): string | null {
  return ms === null ? null : new Date(ms).toISOString();
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly retryAfter?: number,
  ) {
    super(message);
  }
}

/** StubSsoAuthAdapter 와 같은 토큰 형식: user:{id}[:{우선순위}] · admin:{id} */
function verify(authorization: string | null): Identity {
  if (!authorization) throw new HttpError(401, 'missing token');
  const token = authorization.startsWith('Bearer ') ? authorization.slice(7) : authorization;
  const p = token.split(':');
  if (p.length >= 2 && p[0] === 'user' && p[1]) {
    const priority = p.length >= 3 ? Number(p[2]) : 0;
    if (!Number.isInteger(priority)) throw new HttpError(401, 'invalid token');
    return { userId: p[1], priority, admin: false };
  }
  if (p.length === 2 && p[0] === 'admin' && p[1]) return { userId: p[1], priority: 0, admin: true };
  throw new HttpError(401, 'invalid token');
}

export class DemoBackend {
  private readonly opts: DemoOptions;
  private memory: State | null = null;

  constructor(options: Partial<DemoOptions> = {}) {
    this.opts = {
      ...DEFAULT_DEMO_OPTIONS,
      storage: null,
      now: () => Date.now(),
      ...options,
    };
  }

  /** 저장된 데모 데이터를 지운다 */
  reset(): void {
    this.memory = emptyState();
    this.save(this.memory);
  }

  /** fetch 와 같은 모양. createApi({ fetchImpl }) 에 넘긴다. */
  readonly fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://demo.local');
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = new Headers(init?.headers);
    const body = typeof init?.body === 'string' && init.body ? (JSON.parse(init.body) as Record<string, unknown>) : {};
    const path = url.pathname.replace(/^\/api(?=\/)/, '');

    // 진짜 네트워크처럼 응답을 한 박자 늦춘다.
    await new Promise((r) => setTimeout(r, 40));
    if (init?.signal?.aborted) throw new DOMException('aborted', 'AbortError');

    try {
      const state = this.load();
      this.advance(state);
      const result = this.route(state, method, path, url.searchParams, headers, body);
      this.advance(state);
      this.save(state);
      if (result.status === 204 || result.body === undefined) return new Response(null, { status: result.status });
      if (typeof result.body === 'string') {
        return new Response(result.body, { status: result.status, headers: { 'Content-Type': 'text/plain;charset=UTF-8' } });
      }
      return json(result.status, result.body);
    } catch (e) {
      if (e instanceof HttpError) {
        const h: Record<string, string> = { 'Content-Type': 'application/json' };
        if (e.retryAfter !== undefined) h['Retry-After'] = String(e.retryAfter);
        return new Response(JSON.stringify({ error: e.message }), { status: e.status, headers: h });
      }
      return json(500, { error: 'Internal Server Error' });
    }
  };

  /* ───────── 라우팅 (app 모듈의 컨트롤러 4개) ───────── */

  private route(
    s: State,
    method: string,
    path: string,
    query: URLSearchParams,
    headers: Headers,
    body: Record<string, unknown>,
  ): { status: number; body?: unknown } {
    const auth = headers.get('Authorization');
    let m: RegExpMatchArray | null;

    if (method === 'POST' && path === '/reservations') return this.reserve(s, verify(auth), headers.get('Idempotency-Key'), body);
    if (method === 'GET' && (m = path.match(/^\/tickets\/([^/]+)$/))) {
      verify(auth);
      return { status: 200, body: this.ticket(s, decodeURIComponent(m[1] ?? '')) };
    }
    if (method === 'DELETE' && (m = path.match(/^\/reservations\/([^/]+)$/))) return this.cancel(s, verify(auth), decodeURIComponent(m[1] ?? ''));
    if (method === 'GET' && (m = path.match(/^\/reservations\/([^/]+)$/))) return this.detail(s, verify(auth), decodeURIComponent(m[1] ?? ''));

    if (method === 'GET' && path === '/admin/policy') {
      requireAdmin(auth);
      return { status: 200, body: { ...s.policy } };
    }
    if (method === 'POST' && path === '/admin/policy/switch') {
      requireAdmin(auth);
      return { status: 202, body: this.requestSwitch(s, body.target) };
    }
    if (method === 'GET' && (m = path.match(/^\/admin\/policy\/switch\/([^/]+)$/))) {
      requireAdmin(auth);
      const rec = s.switches[decodeURIComponent(m[1] ?? '')];
      if (!rec) throw new HttpError(404, '전환 요청이 없습니다');
      return {
        status: 200,
        body: { ...rec, requestedAt: iso(rec.requestedAt), finishedAt: iso(rec.finishedAt) },
      };
    }

    if (method === 'POST' && path === '/devices/qr/scan') return this.scan(s, body.payload);

    if (method === 'GET' && path === '/policy') {
      verify(auth);
      return {
        status: 200,
        body: {
          ...s.policy,
          timing: POLICIES[s.policy.name],
          phase: this.phase(s),
          graceSeconds: this.opts.graceSeconds,
          collectWindowSeconds: this.opts.collectWindowSeconds,
          maxSlotsPerDay: MAX_SLOTS_PER_DAY[s.policy.name] ?? null,
          minPartySize: this.opts.minPartySize,
        },
      };
    }
    if (method === 'GET' && path === '/slots') return this.slots(s, verify(auth), query);
    if (method === 'GET' && path === '/credits/me') {
      const who = verify(auth);
      return {
        status: 200,
        body: {
          balance: this.balance(s, who.userId),
          held: this.held(s, who.userId),
          renewsAt: new Date(creditPeriodOf(this.opts.now()).renewsAtMs).toISOString(),
        },
      };
    }
    if (method === 'GET' && path === '/queue') {
      verify(auth);
      return { status: 200, body: { depth: s.queue.length } };
    }
    throw new HttpError(404, 'Not Found');
  }

  /* ───────── 접수 ───────── */

  private reserve(s: State, who: Identity, key: string | null, body: Record<string, unknown>) {
    const roomId = typeof body.roomId === 'string' ? body.roomId.trim() : '';
    const startsAt = canonicalInstant(body.startsAt);
    const bid = body.bidAmount === undefined || body.bidAmount === null ? 0 : Number(body.bidAmount);
    if (!roomId || roomId.includes('@') || !startsAt) throw new HttpError(400, 'roomId와 startsAt이 필요합니다');
    if (!key) throw new HttpError(400, 'Idempotency-Key is required');
    if (!Number.isInteger(bid) || bid < 0) throw new HttpError(400, 'bidAmount must be >= 0');
    const party = this.partyOf(who.userId, bid, body.companions);

    const id = requestId(who.userId, key);
    const existing = s.reservations[id];
    if (existing) return { status: 202, body: ticketOf(existing) };
    if (s.queue.some((q) => q.id === id)) return { status: 202, body: { ticketId: id, status: 'PENDING', detail: null } };

    if (this.isTaken(s, roomId, startsAt)) throw new HttpError(409, '이미 마감된 슬롯입니다');

    const now = this.opts.now();
    s.queue.push({
      id,
      userId: who.userId,
      priority: who.priority,
      roomId,
      startsAt,
      bidAmount: party.reduce((n, m) => n + m.bid, 0),
      sequence: ++s.sequence,
      requestedAt: now,
      readyAt: now + this.opts.queueDelayMs,
      party,
    });
    return { status: 202, body: { ticketId: id, status: 'PENDING', detail: null } };
  }

  /** ReservationIntake.partyOf 와 같은 검사: 최소 인원, 학번 중복, 자기 자신 */
  private partyOf(selfId: string, selfBid: number, raw: unknown): Member[] {
    const party: Member[] = [{ userId: selfId, bid: selfBid }];
    const seen = new Set([selfId]);
    for (const c of Array.isArray(raw) ? raw : []) {
      const rec = (c ?? {}) as Record<string, unknown>;
      const id = typeof rec.studentId === 'string' ? rec.studentId.trim() : '';
      const b = rec.bidAmount === undefined || rec.bidAmount === null ? 0 : Number(rec.bidAmount);
      if (!id || !Number.isInteger(b) || b < 0) throw new HttpError(400, '동행 학번과 입찰액을 확인해 주세요');
      if (seen.has(id)) throw new HttpError(400, `duplicate member: ${id}`);
      seen.add(id);
      party.push({ userId: id, bid: b });
    }
    if (party.length < this.opts.minPartySize) {
      throw new HttpError(400, `at least ${this.opts.minPartySize} members are required (with the requester)`);
    }
    return party;
  }

  private ticket(s: State, id: string) {
    const r = s.reservations[id];
    return r ? ticketOf(r) : { ticketId: id, status: 'PENDING', detail: null };
  }

  private cancel(s: State, who: Identity, id: string) {
    const r = s.reservations[id];
    if (!r) throw new HttpError(404, '예약이 없습니다');
    if (r.userId !== who.userId) throw new HttpError(403, '본인 예약이 아닙니다');
    if (r.status !== 'REQUESTED' && r.status !== 'CONFIRMED') throw new HttpError(409, '취소할 수 없는 상태입니다');
    const before = r.status;
    this.transition(r, 'CANCELED', 'USER_CANCELED');
    if (before === 'REQUESTED') this.refund(s, id);
    if (before === 'CONFIRMED') s.delayed = s.delayed.filter((d) => d.key !== `no-show:${id}`);
    return { status: 200 };
  }

  private detail(s: State, who: Identity, id: string) {
    const r = s.reservations[id];
    if (!r) throw new HttpError(404, '예약이 없습니다');
    if (!who.admin && !r.party.some((m) => m.userId === who.userId)) throw new HttpError(403, '본인 예약이 아닙니다');
    return {
      status: 200,
      body: {
        id: r.id,
        status: r.status,
        reason: r.reason,
        roomId: r.roomId,
        startsAt: r.startsAt,
        policyName: r.policyName,
        policyVersion: r.policyVersion,
        bidAmount: r.bidAmount,
        chargedAmount: r.chargedAmount,
        requestedAt: iso(r.requestedAt),
        updatedAt: iso(r.updatedAt),
        checkInDeadline: iso(Date.parse(r.startsAt) + this.opts.graceSeconds * 1000),
        members: r.party.map((m) => ({ studentId: m.userId, bidAmount: m.bid, requester: m.userId === r.userId })),
      },
    };
  }

  private slots(s: State, _who: Identity, query: URLSearchParams) {
    const from = Date.parse(query.get('from') ?? '');
    const to = Date.parse(query.get('to') ?? '');
    if (Number.isNaN(from) || Number.isNaN(to)) throw new HttpError(400, 'from과 to는 ISO-8601 시각이어야 합니다');
    if (to <= from) throw new HttpError(400, 'to는 from보다 뒤여야 합니다');
    if (to - from > 8 * 86_400_000) throw new HttpError(400, '한 번에 8일까지만 조회할 수 있습니다');
    const rooms = new Set((query.get('roomId') ?? '').split(',').map((x) => x.trim()).filter(Boolean));
    const taken = Object.values(s.reservations)
      .filter((r) => r.status === 'CONFIRMED' || r.status === 'CHECKED_IN')
      .filter((r) => {
        const t = Date.parse(r.startsAt);
        return t >= from && t < to && (rooms.size === 0 || rooms.has(r.roomId));
      })
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.roomId.localeCompare(b.roomId))
      .map((r) => ({ roomId: r.roomId, startsAt: r.startsAt, taken: true }));
    return { status: 200, body: taken };
  }

  private scan(s: State, payload: unknown) {
    if (typeof payload !== 'string' || !payload.startsWith('RSV:')) throw new HttpError(400, 'unknown QR payload');
    const r = s.reservations[payload.slice(4)];
    if (!r) return { status: 200, body: { checkedIn: false, result: 'NOT_FOUND' } };
    if (r.status !== 'CONFIRMED') return { status: 200, body: { checkedIn: false, result: 'NO_OP' } };
    this.transition(r, 'CHECKED_IN', null);
    s.delayed = s.delayed.filter((d) => d.key !== `no-show:${r.id}`);
    return { status: 200, body: { checkedIn: true, result: 'APPLIED' } };
  }

  /* ───────── 정책 전환 ───────── */

  private phase(s: State): Phase {
    const rec = s.activeSwitch ? s.switches[s.activeSwitch] : undefined;
    if (!rec) return 'ACTIVE';
    return rec.state === 'DRAINING' ? 'DRAINING' : 'SWITCH_REQUESTED';
  }

  private collectionOpen(s: State): boolean {
    return Object.values(s.reservations).some((r) => r.status === 'REQUESTED');
  }

  private requestSwitch(s: State, rawTarget: unknown): string {
    const target = typeof rawTarget === 'string' ? rawTarget : '';
    if (!(target in POLICIES)) throw new HttpError(400, `unknown policy: ${target}`);
    if (s.policy.name === target) throw new HttpError(409, `이미 ${target} 정책이다`);
    if (this.collectionOpen(s)) throw new HttpError(409, '접수 창이 열려 있는 동안은 전환할 수 없다');
    if (s.activeSwitch) throw new HttpError(409, '다른 전환이 진행 중이다');
    const now = this.opts.now();
    const id = `SW-${requestId('switch', String(now)).slice(2)}`;
    s.switches[id] = {
      switchId: id,
      target: target as PolicyName,
      state: 'REQUESTED',
      fromVersion: s.policy.version,
      toVersion: s.policy.version,
      message: null,
      requestedAt: now,
      finishedAt: null,
    };
    s.activeSwitch = id;
    return id;
  }

  /* ───────── 시간이 흐르며 일어나는 일 (워커·지연 메시지·전환 스레드 대신) ───────── */

  private advance(s: State): void {
    const now = this.opts.now();

    // 정책 전환: REQUESTED → (간격) → DRAINING → (간격) → COMPLETED
    const rec = s.activeSwitch ? s.switches[s.activeSwitch] : undefined;
    if (rec) {
      const step = this.opts.switchStepMs;
      if (rec.state === 'REQUESTED' && now >= rec.requestedAt + step) rec.state = 'DRAINING';
      if (rec.state === 'DRAINING' && now >= rec.requestedAt + 2 * step) {
        if (this.collectionOpen(s)) {
          rec.state = 'FAILED';
          rec.message = '전환 직전에 보류 요청이 생겨 취소했다';
        } else {
          s.policy = { name: rec.target, version: s.policy.version + 1 };
          rec.state = 'COMPLETED';
          rec.toVersion = s.policy.version;
        }
        rec.finishedAt = now;
        s.activeSwitch = null;
      }
    }

    // 지연 메시지: 시각이 된 것부터
    const due = s.delayed.filter((d) => d.dueAt <= now).sort((a, b) => a.dueAt - b.dueAt);
    s.delayed = s.delayed.filter((d) => d.dueAt > now);
    for (const d of due) {
      if (d.type === 'NO_SHOW') {
        const r = s.reservations[d.subject];
        if (r && r.status === 'CONFIRMED') this.transition(r, 'CANCELED', 'NO_SHOW');
      } else {
        this.closeSlot(s, d.subject);
      }
    }

    // 큐 소비. 전환이 배정을 멈춘(DRAINING) 동안은 꺼내지 않는다.
    if (this.phase(s) === 'DRAINING') return;
    s.queue.sort((a, b) => a.sequence - b.sequence);
    while (s.queue.length > 0 && (s.queue[0]?.readyAt ?? Infinity) <= now) {
      const q = s.queue.shift();
      if (q) this.process(s, q);
    }
  }

  private process(s: State, q: QueuedRequest): void {
    if (s.reservations[q.id]) return;
    const policy = s.policy;
    // 구성원 검사(PartyGuard): 같은 시간 중복, 사람별 하루 한도. 접수 중인 입찰도 센다.
    const limit = MAX_SLOTS_PER_DAY[policy.name] ?? null;
    for (const m of q.party) {
      if (this.activeAt(s, m.userId, q.startsAt)) {
        s.reservations[q.id] = this.record(q, 'REJECTED', 'MEMBER_TIME_CONFLICT', 0);
        return;
      }
      if (limit !== null && this.slotsUsedOnDay(s, m.userId, q.startsAt) >= limit) {
        s.reservations[q.id] = this.record(q, 'REJECTED', 'DAILY_LIMIT', 0);
        return;
      }
    }
    if (POLICIES[policy.name] === 'AT_CLOSE') {
      // 보류. 입찰액이 있으면 구성원마다 자기 몫을 묶고, 한 명이라도 모자라면 바로 거절한다.
      if (q.bidAmount > 0 && !this.reserveCredits(s, q.id, q.party)) {
        s.reservations[q.id] = this.record(q, 'REJECTED', 'INSUFFICIENT_CREDIT', 0);
        return;
      }
      s.reservations[q.id] = this.record(q, 'REQUESTED', null, 0);
      const key = slotKey(q.roomId, q.startsAt);
      if (!s.delayed.some((d) => d.key === `slot-close:${key}`)) {
        const closeAt = Math.min(this.opts.now() + this.opts.collectWindowSeconds * 1000, Date.parse(q.startsAt));
        s.delayed.push({ key: `slot-close:${key}`, type: 'SLOT_CLOSE', subject: key, dueAt: closeAt });
      }
      return;
    }
    // FCFS: 큐에서 먼저 꺼낸 요청이 이긴다.
    if (this.isTaken(s, q.roomId, q.startsAt)) {
      s.reservations[q.id] = this.record(q, 'REJECTED', 'SLOT_TAKEN', 0);
    } else {
      this.confirm(s, this.record(q, 'CONFIRMED', null, 0), 0);
    }
  }

  /** 그 사람이 그 시각에 들어가 있는 진행 중 예약(접수 중·확정·입실)이 있는가 */
  private activeAt(s: State, userId: string, startsAt: string): boolean {
    return Object.values(s.reservations).some(
      (r) =>
        r.startsAt === startsAt &&
        (r.status === 'REQUESTED' || r.status === 'CONFIRMED' || r.status === 'CHECKED_IN') &&
        r.party.some((m) => m.userId === userId),
    );
  }

  private slotsUsedOnDay(s: State, userId: string, startsAt: string): number {
    const day = kstDay(startsAt);
    return Object.values(s.reservations).filter((r) => {
      if (!r.party.some((m) => m.userId === userId) || kstDay(r.startsAt) !== day) return false;
      if (r.status === 'REJECTED') return false;
      if (r.status === 'CANCELED') return r.reason === 'NO_SHOW';
      return true;
    }).length;
  }

  private closeSlot(s: State, key: string): void {
    const at = key.lastIndexOf('@');
    const roomId = key.slice(0, at);
    const startsAt = key.slice(at + 1);
    const held = Object.values(s.reservations)
      .filter((r) => r.status === 'REQUESTED' && r.roomId === roomId && r.startsAt === startsAt)
      .sort((a, b) => a.sequence - b.sequence);
    if (held.length === 0) return;
    const taken = this.isTaken(s, roomId, startsAt);
    const policy = s.policy.name;

    let winner: Reservation | null = null;
    if (!taken) {
      if (policy === 'CREDIT_BETTING') {
        winner = [...held].filter((r) => r.bidAmount >= 1).sort((a, b) => b.bidAmount - a.bidAmount || a.sequence - b.sequence)[0] ?? null;
      } else if (policy === 'PRIORITY') {
        winner = [...held].sort((a, b) => b.priority - a.priority || a.sequence - b.sequence)[0] ?? null;
      } else {
        winner = held[0] ?? null;
      }
    }

    for (const r of held) {
      r.policyName = policy;
      r.policyVersion = s.policy.version;
      if (r === winner) {
        const charge = policy === 'CREDIT_BETTING' ? r.bidAmount : 0;
        this.confirm(s, r, charge);
        this.settle(s, r.id, charge);
      } else {
        let reason = taken ? 'SLOT_TAKEN' : 'LOST_FCFS';
        if (policy === 'PRIORITY' && !taken) reason = 'LOWER_PRIORITY';
        if (policy === 'CREDIT_BETTING') reason = r.bidAmount < 1 ? 'NO_BID' : taken ? 'SLOT_TAKEN' : 'OUTBID';
        this.transition(r, 'REJECTED', reason);
        this.refund(s, r.id);
      }
    }
  }

  private confirm(s: State, r: Reservation, charge: number): void {
    r.chargedAmount = charge;
    this.transition(r, 'CONFIRMED', null);
    s.reservations[r.id] = r;
    s.delayed.push({
      key: `no-show:${r.id}`,
      type: 'NO_SHOW',
      subject: r.id,
      dueAt: Date.parse(r.startsAt) + this.opts.graceSeconds * 1000,
    });
  }

  /** 상태 전이. ReservationState 가 허용하는 것만 바꾼다. */
  private transition(r: Reservation, to: Status, reason: string | null): void {
    const allowed: Readonly<Record<Status, readonly Status[]>> = {
      REQUESTED: ['CONFIRMED', 'REJECTED', 'CANCELED'],
      CONFIRMED: ['CHECKED_IN', 'CANCELED'],
      CHECKED_IN: ['COMPLETED'],
      REJECTED: [],
      CANCELED: [],
      COMPLETED: [],
    };
    if (r.status !== to && !allowed[r.status].includes(to) && !(r.status === 'CONFIRMED' && to === 'CONFIRMED')) return;
    r.status = to;
    r.reason = reason;
    r.updatedAt = this.opts.now();
  }

  private record(q: QueuedRequest, status: Status, reason: string | null, charged: number): Reservation {
    return {
      id: q.id,
      userId: q.userId,
      priority: q.priority,
      roomId: q.roomId,
      startsAt: q.startsAt,
      status,
      reason,
      bidAmount: q.bidAmount,
      chargedAmount: charged,
      sequence: q.sequence,
      requestedAt: q.requestedAt,
      updatedAt: this.opts.now(),
      party: q.party,
      ...this.policyStamp(),
    };
  }

  private policyStamp(): { policyName: string; policyVersion: number } {
    const s = this.memory ?? emptyState();
    return { policyName: s.policy.name, policyVersion: s.policy.version };
  }

  private isTaken(s: State, roomId: string, startsAt: string): boolean {
    return Object.values(s.reservations).some(
      (r) => r.roomId === roomId && r.startsAt === startsAt && (r.status === 'CONFIRMED' || r.status === 'CHECKED_IN'),
    );
  }

  /* ───────── 크레딧 장부 (CreditLedger) ───────── */

  /** 현재 학기의 잔액. 처음이거나 학기가 바뀌었으면 지급량으로 (다시) 만든다. 남은 잔액은 이월하지 않는다 */
  private balance(s: State, userId: string): number {
    const period = creditPeriodOf(this.opts.now()).id;
    if (s.balances[userId] === undefined || s.balancePeriods[userId] !== period) {
      s.balances[userId] = this.opts.initialCredits;
      s.balancePeriods[userId] = period;
    }
    return s.balances[userId] ?? 0;
  }

  private held(s: State, userId: string): number {
    const period = creditPeriodOf(this.opts.now()).id;
    return Object.values(s.holds)
      .filter((h) => h.userId === userId && h.period === period)
      .reduce((n, h) => n + h.amount, 0);
  }

  /** 구성원 전원의 몫을 보류한다. 한 명이라도 모자라면 이미 묶은 몫을 돌려주고 false */
  private reserveCredits(s: State, id: string, party: readonly Member[]): boolean {
    const period = creditPeriodOf(this.opts.now()).id;
    for (const m of party) {
      if (m.bid === 0) continue;
      const key = `${id}|${m.userId}`;
      if (s.holds[key]) continue;
      const available = this.balance(s, m.userId);
      if (available < m.bid) {
        this.release(s, id, () => 0);
        return false;
      }
      s.balances[m.userId] = available - m.bid;
      s.holds[key] = { reservationId: id, userId: m.userId, amount: m.bid, period };
    }
    return true;
  }

  /** 보류를 풀고 각자 (자기 몫 - 자기 부담분)을 돌려준다. 지난 학기 몫은 돌려주지 않는다 */
  private release(s: State, id: string, owedOf: (userId: string) => number): void {
    const period = creditPeriodOf(this.opts.now()).id;
    for (const [key, h] of Object.entries(s.holds)) {
      if (h.reservationId !== id) continue;
      delete s.holds[key];
      if (h.period !== period) continue; // 지난 학기 크레딧은 이미 초기화되었다
      s.balances[h.userId] = this.balance(s, h.userId) + (h.amount - owedOf(h.userId));
    }
  }

  /** CreditLedger.splitCharge 와 같다: 몫에 비례해 나누고, 나머지는 몫이 큰 사람부터 1씩 */
  private settle(s: State, id: string, charge: number): void {
    const holds = Object.values(s.holds).filter((h) => h.reservationId === id);
    const total = holds.reduce((n, h) => n + h.amount, 0);
    const pay = Math.min(Math.max(charge, 0), total);
    const owed = new Map<string, number>();
    let assigned = 0;
    for (const h of holds) {
      const share = total === 0 ? 0 : Math.floor((pay * h.amount) / total);
      owed.set(h.userId, share);
      assigned += share;
    }
    const byAmount = [...holds].sort((a, b) => b.amount - a.amount);
    for (let i = 0; assigned < pay && byAmount.length > 0; i = (i + 1) % byAmount.length) {
      const h = byAmount[i]!;
      if ((owed.get(h.userId) ?? 0) < h.amount) {
        owed.set(h.userId, (owed.get(h.userId) ?? 0) + 1);
        assigned++;
      }
    }
    this.release(s, id, (u) => owed.get(u) ?? 0);
  }

  private refund(s: State, id: string): void {
    this.release(s, id, () => 0);
  }

  /* ───────── 저장 ───────── */

  private load(): State {
    const storage = this.opts.storage;
    if (storage) {
      try {
        const raw = storage.getItem(this.opts.storageKey);
        const parsed = raw ? (JSON.parse(raw) as State) : null;
        this.memory = parsed && parsed.v === 1 ? parsed : (this.memory ?? emptyState());
      } catch {
        this.memory = this.memory ?? emptyState();
      }
    }
    this.memory = this.memory ?? emptyState();
    return this.memory;
  }

  private save(state: State): void {
    this.memory = state;
    try {
      this.opts.storage?.setItem(this.opts.storageKey, JSON.stringify(state));
    } catch {
      // 저장소를 못 쓰면 이 탭이 열려 있는 동안만 기억한다.
    }
  }
}

function requireAdmin(authorization: string | null): void {
  if (!verify(authorization).admin) throw new HttpError(403, '관리자만 사용할 수 있습니다');
}

function ticketOf(r: Reservation) {
  const status =
    r.status === 'REQUESTED'
      ? 'PENDING'
      : r.status === 'REJECTED'
        ? 'REJECTED'
        : r.status === 'CANCELED'
          ? 'CANCELED'
          : 'CONFIRMED';
  return { ticketId: r.id, status, detail: r.reason };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
