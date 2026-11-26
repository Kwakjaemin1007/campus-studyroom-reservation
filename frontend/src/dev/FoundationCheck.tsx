import { useEffect, useMemo, useState } from 'react';
import { BUILDINGS, openHours } from '@/catalog/campusCatalog';
import { appConfig } from '@/config';
import { supported, unsupported, type Capability } from '@/domain/capability';
import { isApiError } from '@/domain/errors';
import type { CreditBalance } from '@/domain/reservation';
import type { Session } from '@/domain/session';
import { formatCountdown, kstDateKey, kstSlotStart } from '@/lib/time';
import { policyUIRegistry, usePolicyDraft, type PolicyFormContext } from '@/policy-ui';
import { api, session as sessionPort } from '@/services';

/**
 * 기반 점검 화면.
 *  1) 백엔드 연결: 어댑터가 실제 서버와 맞물리는지, 서버에 어떤 조회 API 가 있는지
 *  2) 정책 UI 모듈: 정책 이름만 바꿔 신청서가 갈아 끼워지는지
 *  3) 디자인 토큰: 색·글자·면·상태 표현
 */

type CheckState = 'idle' | 'running' | 'ok' | 'missing' | 'denied' | 'error';

interface CheckRow {
  readonly id: string;
  readonly endpoint: string;
  readonly purpose: string;
  readonly state: CheckState;
  readonly note: string;
}

const INITIAL_ROWS: readonly CheckRow[] = [
  { id: 'ticket', endpoint: 'GET /tickets/{id}', purpose: '번호표 조회', state: 'idle', note: '' },
  { id: 'admin', endpoint: 'GET /admin/policy', purpose: '현재 정책 (관리자)', state: 'idle', note: '' },
  { id: 'qr', endpoint: 'POST /devices/qr/scan', purpose: 'QR 체크인', state: 'idle', note: '' },
  { id: 'policy', endpoint: 'GET /policy', purpose: '현재 정책 (학생)', state: 'idle', note: '' },
  { id: 'slots', endpoint: 'GET /slots', purpose: '타임테이블 점유 현황', state: 'idle', note: '' },
  { id: 'credits', endpoint: 'GET /credits/me', purpose: '보유 크레딧', state: 'idle', note: '' },
  { id: 'queue', endpoint: 'GET /queue', purpose: '대기열 부하', state: 'idle', note: '' },
];

const STATE_LABEL: Readonly<Record<CheckState, { text: string; chip: string }>> = {
  idle: { text: '대기', chip: 'chip-neutral' },
  running: { text: '확인 중', chip: 'chip-neutral' },
  ok: { text: '연결됨', chip: 'chip-ok' },
  missing: { text: '백엔드에 없음', chip: 'chip-warn' },
  denied: { text: '권한 없음', chip: 'chip-neutral' },
  error: { text: '오류', chip: 'chip-danger' },
};

function describeError(e: unknown): { state: CheckState; note: string } {
  if (isApiError(e)) {
    if (e.kind === 'forbidden') return { state: 'denied', note: '관리자 토큰으로 로그인하면 확인됩니다.' };
    if (e.kind === 'unauthorized') return { state: 'denied', note: '로그인이 필요합니다.' };
    return { state: 'error', note: `${e.userMessage}${e.status ? ` (HTTP ${e.status})` : ''}` };
  }
  return { state: 'error', note: e instanceof Error ? e.message : '알 수 없는 오류' };
}

function capabilityRow<T>(result: Capability<T>, describe: (value: T) => string): { state: CheckState; note: string } {
  return result.supported
    ? { state: 'ok', note: describe(result.value) }
    : { state: 'missing', note: '화면은 이 값을 "알 수 없음"으로 표시합니다.' };
}

function useSession(): Session | null {
  const [current, setCurrent] = useState<Session | null>(sessionPort.current());
  useEffect(() => sessionPort.subscribe(setCurrent), []);
  return current;
}

function BackendSection() {
  const current = useSession();
  const [token, setToken] = useState(current?.token ?? 'user:u-101:1');
  const [signInError, setSignInError] = useState<string | null>(null);
  const [rows, setRows] = useState<readonly CheckRow[]>(INITIAL_ROWS);
  const [running, setRunning] = useState(false);

  function patch(id: string, next: { state: CheckState; note: string }) {
    setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...next } : r)));
  }

  function signIn() {
    try {
      sessionPort.signIn(token);
      setSignInError(null);
    } catch (e) {
      setSignInError(e instanceof Error ? e.message : '로그인에 실패했습니다.');
    }
  }

  async function run() {
    setRunning(true);
    setRows(INITIAL_ROWS.map((r) => ({ ...r, state: 'running' })));
    const probeId = 'R-00000000-0000-0000-0000-000000000000';
    const day = kstDateKey(new Date());
    const steps: ReadonlyArray<readonly [string, () => Promise<{ state: CheckState; note: string }>]> = [
      ['ticket', async () => ({ state: 'ok', note: `응답 상태: ${(await api.reservations.getTicket(probeId)).phase}` })],
      [
        'admin',
        async () => {
          const p = await api.adminPolicy.getActivePolicy();
          return { state: 'ok', note: `${p.name} v${p.version}` };
        },
      ],
      [
        'qr',
        async () => {
          // 형식이 틀린 값을 보내 엔드포인트가 살아 있는지만 본다. 예약 상태는 바뀌지 않는다.
          const r = await api.qrDevice.scanRaw('PING');
          return { state: 'ok', note: `형식 검사 응답: ${r.outcome}` };
        },
      ],
      [
        'policy',
        async () =>
          capabilityRow(await api.policyStatus.getStatus(), (v) => `${v.policy.name} v${v.policy.version}`),
      ],
      [
        'slots',
        async () =>
          capabilityRow(
            await api.slotBoard.getTaken({
              roomIds: [BUILDINGS[0]?.rooms[0]?.roomId ?? 'ENG-KC-1'],
              from: kstSlotStart(day, 9),
              to: kstSlotStart(day, 22),
            }),
            (taken) => `차 있는 칸 ${taken.size}개`,
          ),
      ],
      [
        'credits',
        async () => capabilityRow(await api.credits.getBalance(), (c) => `보유 ${c.available}, 묶임 ${c.held}`),
      ],
      [
        'queue',
        async () => capabilityRow(await api.queue.getLoad(), (q) => `대기 ${q.waiting}건`),
      ],
    ];
    for (const [id, step] of steps) {
      try {
        patch(id, await step());
      } catch (e) {
        patch(id, describeError(e));
      }
    }
    setRunning(false);
  }

  return (
    <section className="surface">
      <header className="hairline-b flex flex-wrap items-center justify-between gap-3 px-4 py-3">
        <div>
          <h2 className="m-0 text-md">백엔드 연결</h2>
          <p className="m-0 text-xs text-sub">
            기준 경로 <code className="font-mono">{appConfig.apiBase}</code> · 개발 서버가 localhost:8080 으로 넘겨 줍니다.
          </p>
        </div>
        <button type="button" className="btn-primary" onClick={() => void run()} disabled={running || !current}>
          {running ? '확인 중' : '연결 확인'}
        </button>
      </header>

      <div className="hairline-b px-4 py-3">
        <label className="field-label" htmlFor="stub-token">
          개발용 가짜 SSO 토큰
        </label>
        <div className="flex flex-wrap items-center gap-3">
          <input
            id="stub-token"
            className="field-input min-w-[220px] flex-1 font-mono"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            spellCheck={false}
            autoComplete="off"
          />
          <button type="button" className="btn-secondary" onClick={signIn}>
            이 토큰으로 로그인
          </button>
          <div className="text-sm text-sub">
            {current ? (
              <>
                로그인: <span className="text-ink">{current.userId}</span> ·{' '}
                {current.role === 'admin' ? '관리자' : '학생'}
              </>
            ) : (
              '로그인하지 않음'
            )}
          </div>
        </div>
        {signInError ? (
          <p className="field-error">{signInError}</p>
        ) : (
          <p className="field-hint">user:학번 또는 admin:아이디</p>
        )}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="hairline-b text-left text-xs text-sub">
              <th className="px-4 py-2 font-medium">용도</th>
              <th className="px-4 py-2 font-medium">경로</th>
              <th className="px-4 py-2 font-medium">상태</th>
              <th className="px-4 py-2 font-medium">내용</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="hairline-b last:border-b-0">
                <td className="whitespace-nowrap px-4 py-2 text-ink">{r.purpose}</td>
                <td className="whitespace-nowrap px-4 py-2 font-mono text-xs text-sub">{r.endpoint}</td>
                <td className="px-4 py-2">
                  {r.state === 'running' ? (
                    <span className="skeleton inline-block h-5 w-14 align-middle" aria-label="확인 중" />
                  ) : (
                    <span className={STATE_LABEL[r.state].chip}>{STATE_LABEL[r.state].text}</span>
                  )}
                </td>
                <td className="px-4 py-2 text-sub">{r.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const SAMPLE_CREDITS: Readonly<Record<string, Capability<CreditBalance> | null>> = {
  known: supported({ available: 80, held: 20, renewsAt: null }),
  unknown: unsupported('서버에 이 조회 API가 없습니다.'),
  loading: null,
};

function PolicyModuleSection() {
  const names = useMemo(() => [...policyUIRegistry.names(), 'LOTTERY'], []);
  const [name, setName] = useState<string>(names[0] ?? 'FCFS');
  const [creditMode, setCreditMode] = useState<string>('known');
  const { module, matched } = policyUIRegistry.resolve(name);

  const ctx: PolicyFormContext = useMemo(
    () => ({
      slot: { roomId: 'ENG-KC-1', startsAt: kstSlotStart(kstDateKey(new Date()), 14) },
      session: { token: 'user:u-101:2', userId: 'u-101', priority: 2, role: 'student' },
      policy: { name, version: 1 },
      credits: SAMPLE_CREDITS[creditMode] ?? null,
      collectWindowMinutes: appConfig.collectWindowMinutes,
    }),
    [name, creditMode],
  );

  const [draft, setDraft] = usePolicyDraft(module, ctx);

  const errors = module.validate(draft, ctx);
  const valid = Object.keys(errors).length === 0;
  const Form = module.Form;

  return (
    <section className="surface">
      <header className="hairline-b px-4 py-3">
        <h2 className="m-0 text-md">정책 UI 모듈</h2>
        <p className="m-0 text-xs text-sub">
          정책 이름으로 레지스트리에서 모듈을 꺼내 그립니다. 이 화면 코드에는 정책별 분기가 없습니다.
        </p>
      </header>

      <div className="hairline-b flex flex-wrap gap-x-1 px-4" role="tablist" aria-label="정책">
        {names.map((n) => (
          <button
            key={n}
            type="button"
            role="tab"
            aria-selected={n === name}
            className={`-mb-px border-b-2 px-3 py-2 text-sm ${
              n === name ? 'border-ku font-medium text-ink' : 'border-transparent text-sub hover:text-ink'
            }`}
            onClick={() => setName(n)}
          >
            {policyUIRegistry.has(n) ? policyUIRegistry.labelOf(n) : `${n} (미등록)`}
          </button>
        ))}
      </div>

      <div className="grid gap-4 p-4 md:grid-cols-2">
        <div className="flex flex-col gap-3">
          <div className="text-xs text-sub">
            {matched ? module.rule : '등록된 모듈이 없어 대체 모듈이 그려집니다.'}
          </div>
          <Form draft={draft} onChange={setDraft} errors={errors} ctx={ctx} disabled={false} />
          <button type="button" className="btn-primary self-start" disabled={!valid}>
            {module.submitLabel(draft, ctx)}
          </button>
        </div>

        <div className="flex flex-col gap-3">
          {module.needs.includes('credits') ? (
            <div>
              <label className="field-label" htmlFor="credit-mode">
                보유 크레딧 조회 결과 (미리보기용)
              </label>
              <select
                id="credit-mode"
                className="field-input"
                value={creditMode}
                onChange={(e) => setCreditMode(e.target.value)}
              >
                <option value="known">조회됨: 보유 80, 묶임 20</option>
                <option value="unknown">백엔드에 조회 API 없음</option>
                <option value="loading">불러오는 중</option>
              </select>
            </div>
          ) : null}
          <div>
            <div className="field-label">서버로 보내는 값</div>
            <pre className="m-0 overflow-x-auto rounded-md border border-line bg-canvas p-3 font-mono text-xs text-ink">
              {valid ? JSON.stringify(module.toSubmission(draft, ctx), null, 2) : '검증을 통과해야 보냅니다.'}
            </pre>
          </div>
          <div>
            <div className="field-label">대기 중 문구</div>
            <p className="banner-neutral m-0">{module.pendingCopy(ctx).body}</p>
          </div>
        </div>
      </div>
    </section>
  );
}

const SWATCHES: ReadonlyArray<{ name: string; hex: string; className: string; role: string }> = [
  { name: 'white', hex: '#FFFFFF', className: 'bg-white', role: '면' },
  { name: 'canvas', hex: '#F8FAFC', className: 'bg-canvas', role: '바탕' },
  { name: 'line', hex: '#E2E8F0', className: 'bg-line', role: '1px 구분선' },
  { name: 'ink', hex: '#0F172A', className: 'bg-ink', role: '본문' },
  { name: 'sub', hex: '#475569', className: 'bg-sub', role: '보조 글자' },
  { name: 'ku', hex: '#036B3F', className: 'bg-ku', role: '핵심 실행 · 선택 · 활성 탭' },
  { name: 'ku-hover', hex: '#025230', className: 'bg-ku-hover', role: '포인트 hover' },
  { name: 'ku-tint', hex: '#ECFDF5', className: 'bg-ku-tint', role: '선택된 칸 바탕' },
];

function TokenSection() {
  const [remaining, setRemaining] = useState(appConfig.graceMinutes * 60_000);
  useEffect(() => {
    const t = setInterval(() => setRemaining((r) => (r <= 0 ? appConfig.graceMinutes * 60_000 : r - 1000)), 1000);
    return () => clearInterval(t);
  }, []);
  const total = appConfig.graceMinutes * 60_000;
  const hours = openHours().slice(0, 6);

  return (
    <section className="surface">
      <header className="hairline-b px-4 py-3">
        <h2 className="m-0 text-md">디자인 토큰</h2>
        <p className="m-0 text-xs text-sub">무채색 면과 1px 선으로 위계를 만들고, 초록은 실행·선택·활성 표시에만 씁니다.</p>
      </header>

      <div className="grid gap-px bg-line md:grid-cols-2">
        <div className="bg-white p-4">
          <div className="field-label">색</div>
          <ul className="m-0 grid list-none grid-cols-2 gap-2 p-0">
            {SWATCHES.map((s) => (
              <li key={s.name} className="flex items-center gap-2">
                <span className={`h-8 w-8 shrink-0 rounded-md border border-line ${s.className}`} />
                <span className="min-w-0">
                  <span className="block text-sm text-ink">{s.name}</span>
                  <span className="num block text-2xs text-sub">
                    {s.hex} · {s.role}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <div className="bg-white p-4">
          <div className="field-label">글자</div>
          <p className="m-0 text-xl font-semibold">스터디룸 예약 22/30</p>
          <p className="m-0 text-md">본문 강조 16/24 — 공학관 K-Cube 3</p>
          <p className="m-0 text-base">본문 14/22 — 예약이 확정되었습니다. 시작 10분 안에 입실해 주세요.</p>
          <p className="m-0 text-xs text-sub">보조 12/18 — Reservation R-cc0dffd0 · 2026-11-20 14:00</p>
        </div>

        <div className="bg-white p-4">
          <div className="field-label">버튼 · 상태 표식</div>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="btn-primary">
              예약 신청
            </button>
            <button type="button" className="btn-secondary">
              닫기
            </button>
            <button type="button" className="btn-danger">
              예약 취소
            </button>
            <button type="button" className="btn-primary" disabled>
              마감
            </button>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="chip-neutral">대기</span>
            <span className="chip-ok">확정</span>
            <span className="chip-warn">전환 중</span>
            <span className="chip-danger">거절</span>
          </div>
        </div>

        <div className="bg-white p-4">
          <div className="field-label">인라인 알림</div>
          <div className="flex flex-col gap-2">
            <p className="banner-danger m-0">이미 마감된 시간입니다. 다른 시간을 선택해 주세요.</p>
            <p className="banner-warn m-0">배정 방식을 바꾸는 중입니다. 신청은 접수되며 잠시 뒤 처리됩니다.</p>
          </div>
        </div>

        <div className="bg-white p-4">
          <div className="field-label">타임테이블 칸 · 스켈레톤</div>
          <div className="grid grid-cols-6 gap-px overflow-hidden rounded-md border border-line bg-line">
            {hours.map((h) => (
              <div key={`h${h}`} className="num bg-canvas px-2 py-1 text-center text-2xs text-sub">
                {String(h).padStart(2, '0')}:00
              </div>
            ))}
            <div className="flex h-cell items-center justify-center bg-white text-xs text-sub">가능</div>
            <div className="flex h-cell items-center justify-center border border-ku-line bg-ku-tint text-xs font-medium text-ku">
              선택
            </div>
            <div className="flex h-cell items-center justify-center bg-canvas text-xs text-mute">마감</div>
            <div className="h-cell bg-white p-1.5">
              <div className="skeleton h-full w-full" />
            </div>
            <div className="h-cell bg-white p-1.5">
              <div className="skeleton h-full w-full" />
            </div>
            <div className="h-cell bg-white p-1.5">
              <div className="skeleton h-full w-full" />
            </div>
          </div>
        </div>

        <div className="bg-white p-4">
          <div className="field-label">노쇼 카운트다운 (고정폭 숫자)</div>
          <div className="flex items-baseline justify-between">
            <span className="text-sm text-sub">입실까지 남은 시간</span>
            <time className="num text-xl font-semibold text-ink">{formatCountdown(remaining)}</time>
          </div>
          <div
            className="meter mt-2"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round((remaining / total) * 100)}
          >
            <span style={{ width: `${(remaining / total) * 100}%` }} />
          </div>
        </div>
      </div>
    </section>
  );
}

export function FoundationCheck() {
  return (
    <div className="min-h-full bg-canvas">
      <header className="hairline-b bg-white">
        <div className="mx-auto flex max-w-5xl items-baseline justify-between gap-4 px-4 py-3">
          <h1 className="m-0 text-lg">스터디룸 예약 · 기반 점검</h1>
          <span className="text-xs text-sub">프론트엔드 1단계</span>
        </div>
      </header>
      <main className="mx-auto flex max-w-5xl flex-col gap-4 px-4 py-4">
        <BackendSection />
        <PolicyModuleSection />
        <TokenSection />
      </main>
    </div>
  );
}
