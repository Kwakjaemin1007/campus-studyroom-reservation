import { useEffect, useRef, useState } from 'react';
import { watchSwitch } from '@/api';
import { BUILDINGS, CLOSE_HOUR, OPEN_HOUR, openHours } from '@/catalog/campusCatalog';
import { toApiError, type ApiError } from '@/domain/errors';
import type { PolicyName, SwitchProgress } from '@/domain/policy';
import type { Session } from '@/domain/session';
import { usePolling } from '@/hooks/usePolling';
import { kstDateKey, kstDayLabel, kstSlotStart } from '@/lib/time';
import { policyUIRegistry } from '@/policy-ui';
import { api } from '@/services';

const TIMING_COPY = {
  IMMEDIATE: '신청 즉시 배정',
  AT_CLOSE: '접수 마감 시 일괄 배정',
} as const;

const PHASE_COPY = {
  ACTIVE: '정상 운영',
  SWITCH_REQUESTED: '전환 요청됨',
  DRAINING: '진행 중인 배정을 비우는 중',
} as const;

/** 전환 요청 한 건이 지나가는 단계(SwitchStatus.State) */
const STEPS: readonly { state: SwitchProgress['state']; label: string; note: string }[] = [
  { state: 'REQUESTED', label: '요청됨', note: '진행 중인 배정이 끝나기를 기다립니다' },
  { state: 'DRAINING', label: '비우는 중', note: '새 배정을 잠시 멈추고 정책을 바꿉니다' },
  { state: 'COMPLETED', label: '적용 완료', note: '새 정책으로 배정을 다시 시작합니다' },
];

function stepIndex(state: SwitchProgress['state']): number {
  if (state === 'COMPLETED') return 2;
  if (state === 'DRAINING') return 1;
  return 0;
}

/**
 * 관리자 정책 콘솔: 현재 정책, 정책 전환(Feature Toggle 상태 추적), 실시간 점유 현황.
 * 정책 목록은 PolicyUIRegistry 에 등록된 모듈에서 읽는다. 새 정책 모듈을 등록하면 여기에도 나타난다.
 */
export function AdminPolicyConsole({ session }: { session: Session }) {
  const policyPoll = usePolling((signal) => api.policyStatus.getStatus({ signal }), {
    intervalMs: 1500,
    key: session.userId,
  });
  const status = policyPoll.data?.supported ? policyPoll.data.value : null;
  const current = status?.policy ?? null;

  const [target, setTarget] = useState<PolicyName | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [progress, setProgress] = useState<SwitchProgress | null>(null);
  const [watchError, setWatchError] = useState<ApiError | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const stopWatch = useRef<(() => void) | null>(null);
  useEffect(() => () => stopWatch.current?.(), []);

  const modules = policyUIRegistry.list();
  const inFlight = requesting || (progress !== null && !progress.done) || (status?.phase != null && status.phase !== 'ACTIVE');
  const canRequest = target !== null && target !== current?.name && !inFlight;
  const refreshPolicy = policyPoll.refresh;

  async function requestSwitch() {
    if (!canRequest || target === null) return;
    setRequesting(true);
    setError(null);
    setWatchError(null);
    setProgress(null);
    try {
      const { switchId } = await api.adminPolicy.requestSwitch(target);
      stopWatch.current?.();
      stopWatch.current = watchSwitch(api.adminPolicy, switchId, (event) => {
        if (event.progress) setProgress(event.progress);
        setWatchError(event.lastError);
        if (event.progress?.done) refreshPolicy();
      });
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setRequesting(false);
      refreshPolicy();
    }
  }

  return (
    <main className="grid content-start gap-5 px-4 py-5 lg:grid-cols-[minmax(0,560px)_minmax(0,1fr)] lg:gap-6 lg:px-8">
      <div className="flex min-w-0 flex-col gap-5">
        <section className="surface flex flex-col gap-3 p-5" aria-labelledby="current-policy-title">
          <div className="flex items-baseline justify-between gap-3">
            <h2 id="current-policy-title" className="m-0 text-base">
              평소 정책
            </h2>
            {current ? <span className="num text-xs text-sub">버전 {current.version}</span> : null}
          </div>
          {policyPoll.loading ? (
            <span className="skeleton block h-[30px] w-48" aria-label="불러오는 중" />
          ) : current ? (
            <>
              <p className="m-0 text-xl font-semibold">
                {policyUIRegistry.labelOf(current.name)}{' '}
                <span className="font-mono text-sm font-normal text-sub">{current.name}</span>
              </p>
              <p className="m-0 text-sm text-sub">
                {status?.timing ? `${TIMING_COPY[status.timing]} · ` : ''}
                {status?.phase ? PHASE_COPY[status.phase] : '전환 상태를 알 수 없음'}
              </p>
            </>
          ) : (
            <p className="banner-danger m-0" role="alert">
              현재 정책을 불러오지 못했습니다. {policyPoll.error?.userMessage ?? ''}
            </p>
          )}
        </section>

        <section className="surface flex flex-col gap-3 p-5" aria-labelledby="switch-title">
          <h2 id="switch-title" className="m-0 text-base">
            정책 전환
          </h2>
          <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={inFlight}>
            <legend className="sr-only">바꿀 정책</legend>
            {modules.map((m) => {
              const isCurrent = m.policy === current?.name;
              const checked = target === m.policy;
              return (
                <label
                  key={m.policy}
                  className={`flex min-h-[44px] cursor-pointer items-start gap-3 rounded-md border px-3 py-2.5 ${
                    checked ? 'border-ink bg-canvas' : 'border-line bg-white'
                  } ${isCurrent ? 'cursor-default' : ''}`}
                >
                  <input
                    type="radio"
                    name="target-policy"
                    className="mt-1 accent-ink"
                    value={m.policy}
                    checked={checked}
                    disabled={isCurrent}
                    onChange={() => setTarget(m.policy)}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-base font-semibold text-ink">{m.label}</span>
                      <span className="font-mono text-xs text-sub">{m.policy}</span>
                      {isCurrent ? <span className="chip-neutral">현재</span> : null}
                    </span>
                    <span className="block text-xs text-sub">
                      {TIMING_COPY[m.timing]} · {m.rule}
                    </span>
                  </span>
                </label>
              );
            })}
          </fieldset>

          <p className="m-0 text-xs text-sub">
            시험 기간이 아닌 날짜에 적용되는 정책입니다. 전환은 진행 중인 배정이 끝난 뒤 적용되고, 그동안 새 신청은
            접수되며 배정만 잠시 미뤄집니다. 입찰을 모으는 중이면 마감이 끝난 뒤에 바뀝니다. 운영이 끝난 밤에 바꾸는
            것을 권장합니다.
          </p>

          {error ? (
            <div className="banner-danger flex-col gap-0.5" role="alert">
              <strong className="font-semibold">{error.userMessage}</strong>
              {error.serverMessage ? <span>서버 응답: {error.serverMessage}</span> : null}
            </div>
          ) : null}

          <div className="flex justify-end">
            <button type="button" className="btn-primary h-11 px-5" onClick={() => void requestSwitch()} disabled={!canRequest}>
              {requesting ? '요청하는 중' : inFlight ? '전환 진행 중' : '전환 요청'}
            </button>
          </div>
        </section>

        <ExamPeriodPanel policies={modules.map((m) => ({ policy: m.policy, label: m.label }))} onChanged={refreshPolicy} />
      </div>

      <div className="flex min-w-0 flex-col gap-5">
        <SwitchTracker progress={progress} watchError={watchError} />
        <OccupancyPanel adminId={session.userId} />
      </div>
    </main>
  );
}

/**
 * 시험 기간 등록 (11/25 결정). 학사 일정을 보고 기간과 정책을 등록하면, 그 날짜의 자리는 등록한 정책으로 배정한다.
 * 이미 그 날짜에 잡혀 있던 예약은 서버가 취소하고 구성원에게 알린다.
 */
function ExamPeriodPanel({
  policies,
  onChanged,
}: {
  policies: readonly { policy: PolicyName; label: string }[];
  onChanged: () => void;
}) {
  const listPoll = usePolling((signal) => api.adminPolicy.listExamPeriods({ signal }), { intervalMs: 10000, key: 'exam' });
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [policy, setPolicy] = useState<PolicyName>('CREDIT_BETTING');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const periods = listPoll.data ?? [];
  const refreshList = listPoll.refresh;

  async function register() {
    if (!start || !end || end < start) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await api.adminPolicy.registerExamPeriod({ start, end, policy });
      setNotice(
        r.canceled > 0
          ? `등록했습니다. 그 기간에 이미 잡혀 있던 예약 ${r.canceled}건을 취소하고 알렸습니다.`
          : '등록했습니다.',
      );
      setStart('');
      setEnd('');
      refreshList();
      onChanged();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  async function cancel(id: string) {
    setError(null);
    setNotice(null);
    try {
      await api.adminPolicy.cancelExamPeriod(id);
      refreshList();
      onChanged();
    } catch (e) {
      setError(toApiError(e));
    }
  }

  return (
    <section className="surface flex flex-col gap-3 p-5" aria-labelledby="exam-title">
      <h2 id="exam-title" className="m-0 text-base">
        시험 기간
      </h2>
      <p className="m-0 text-xs text-sub">
        이 기간 날짜의 자리는 아래 정책으로 배정합니다. 학생은 하루 전 {CLOSE_HOUR}시까지 신청하고 {CLOSE_HOUR}시에 한꺼번에
        정해지며, 시작일 자리와 남은 자리는 당일에 신청합니다. 시작일은 내일 이후여야 합니다.
      </p>

      {periods.length > 0 ? (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {periods.map((p) => (
            <li key={p.id} className="flex items-center justify-between gap-3 rounded-md border border-line bg-white px-3 py-2">
              <span className="text-sm">
                <span className="num font-semibold text-ink">
                  {p.start} ~ {p.end}
                </span>{' '}
                · {policyUIRegistry.labelOf(p.policy)}
                {p.maxSlotsPerDay !== null ? ` · 하루 ${p.maxSlotsPerDay}시간` : ''}
              </span>
              <button type="button" className="btn-secondary h-8 px-2 text-xs" onClick={() => void cancel(p.id)}>
                취소
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="m-0 text-sm text-sub">등록된 시험 기간이 없습니다.</p>
      )}

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-xs text-sub">
          시작
          <input type="date" className="field-input h-10" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-sub">
          끝
          <input type="date" className="field-input h-10" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-sub">
          정책
          <select className="field-input h-10" value={policy} onChange={(e) => setPolicy(e.target.value)}>
            {policies.map((p) => (
              <option key={p.policy} value={p.policy}>
                {p.label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn-primary h-10 px-4"
          onClick={() => void register()}
          disabled={busy || !start || !end || end < start}
        >
          {busy ? '등록하는 중' : '등록'}
        </button>
      </div>

      {notice ? (
        <p className="banner-neutral m-0" role="status">
          {notice}
        </p>
      ) : null}
      {error ? (
        <div className="banner-danger flex-col gap-0.5" role="alert">
          <strong className="font-semibold">{error.userMessage}</strong>
          {error.serverMessage ? <span>서버 응답: {error.serverMessage}</span> : null}
        </div>
      ) : null}
    </section>
  );
}

function SwitchTracker({ progress, watchError }: { progress: SwitchProgress | null; watchError: ApiError | null }) {
  const reached = progress ? stepIndex(progress.state) : -1;
  const failed = progress?.state === 'FAILED';

  return (
    <section className="surface flex flex-col gap-4 p-5" aria-labelledby="tracker-title" aria-live="polite">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h2 id="tracker-title" className="m-0 text-base">
          전환 진행
        </h2>
        {progress ? (
          <span className="font-mono text-xs text-sub">
            {progress.switchId.slice(0, 11)} · {progress.target}
          </span>
        ) : null}
      </div>

      <ol className="m-0 grid list-none grid-cols-3 gap-2 p-0">
        {STEPS.map((step, i) => {
          const done = !failed && i <= reached;
          const active = !failed && i === reached && progress !== null && !progress.done;
          return (
            <li key={step.state} className="flex flex-col gap-1.5" aria-current={active ? 'step' : undefined}>
              <span className={`block h-0.5 ${done ? 'bg-ink' : 'bg-line'}`} />
              <span className={`text-sm ${done ? 'font-semibold text-ink' : 'text-sub'}`}>{step.label}</span>
              <span className="text-xs text-sub">{step.note}</span>
            </li>
          );
        })}
      </ol>

      {!progress ? (
        <p className="m-0 text-sm text-sub">이 화면에서 요청한 전환이 아직 없습니다.</p>
      ) : failed ? (
        <div className="banner-danger flex-col gap-0.5" role="alert">
          <strong className="font-semibold">전환하지 못했습니다. 정책은 그대로입니다.</strong>
          {progress.message ? <span>서버 응답: {progress.message}</span> : null}
        </div>
      ) : progress.done ? (
        <p className="banner-neutral m-0">
          <span>
            <strong className="font-semibold">{policyUIRegistry.labelOf(progress.target)}</strong> 정책이 적용되었습니다.{' '}
            <span className="num">
              버전 {progress.fromVersion} → {progress.toVersion}
            </span>
          </span>
        </p>
      ) : (
        <p className="banner-warn m-0">전환하는 중입니다. 새 신청의 배정이 잠시 미뤄질 수 있습니다.</p>
      )}
      {watchError && progress && !progress.done ? (
        <p className="m-0 text-xs text-warn">진행 상태를 읽지 못했습니다. 자동으로 다시 확인하는 중입니다.</p>
      ) : null}
    </section>
  );
}

function OccupancyPanel({ adminId }: { adminId: string }) {
  const dateKey = kstDateKey(new Date());
  const allRooms = BUILDINGS.flatMap((b) => b.rooms.map((r) => r.roomId));
  const boardPoll = usePolling(
    (signal) =>
      api.slotBoard.getTaken(
        { roomIds: allRooms, from: kstSlotStart(dateKey, OPEN_HOUR), to: kstSlotStart(dateKey, CLOSE_HOUR) },
        { signal },
      ),
    { intervalMs: 3000, key: `${adminId}|${dateKey}` },
  );
  const queuePoll = usePolling((signal) => api.queue.getLoad({ signal }), { intervalMs: 3000, key: adminId });

  const taken = boardPoll.data?.supported ? boardPoll.data.value : null;
  const slotsPerRoom = openHours().length;
  const rows = BUILDINGS.map((b) => {
    const total = b.rooms.length * slotsPerRoom;
    let used = 0;
    if (taken) {
      for (const key of taken) {
        if (b.rooms.some((r) => key.startsWith(`${r.roomId}@`))) used++;
      }
    }
    return { id: b.id, name: b.name, used, total, pct: total > 0 ? Math.round((used / total) * 100) : 0 };
  });
  const totalUsed = rows.reduce((n, r) => n + r.used, 0);
  const totalSlots = rows.reduce((n, r) => n + r.total, 0);

  return (
    <section className="surface" aria-labelledby="occupancy-title">
      <header className="hairline-b flex flex-wrap items-baseline justify-between gap-x-3 px-5 py-3">
        <h2 id="occupancy-title" className="m-0 text-base">
          실시간 점유 현황
        </h2>
        <span className="num text-xs text-sub">
          오늘 {kstDayLabel(kstSlotStart(dateKey, 12))} {OPEN_HOUR}–{CLOSE_HOUR}시 · 3초마다 갱신
        </span>
      </header>

      {boardPoll.error && !taken ? (
        <div className="banner-danger m-4 items-center justify-between" role="alert">
          <span>점유 현황을 불러오지 못했습니다. {boardPoll.error.userMessage}</span>
          <button type="button" className="btn-secondary h-8 shrink-0 px-2 text-xs" onClick={boardPoll.refresh}>
            다시 시도
          </button>
        </div>
      ) : boardPoll.data && !boardPoll.data.supported ? (
        <p className="m-0 px-5 py-4 text-sm text-sub">이 서버는 점유 현황 조회를 제공하지 않습니다.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm" aria-busy={boardPoll.loading}>
            <thead>
              <tr className="hairline-b bg-canvas text-left text-xs text-sub">
                <th scope="col" className="px-5 py-2 font-medium">건물</th>
                <th scope="col" className="w-28 px-3 py-2 text-right font-medium">확정 슬롯</th>
                <th scope="col" className="w-20 px-3 py-2 text-right font-medium">점유율</th>
                <th scope="col" className="w-[30%] px-5 py-2 font-medium">
                  <span className="sr-only">점유율 막대</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="hairline-b">
                  <th scope="row" className="px-5 py-2.5 text-left font-normal text-ink">{r.name}</th>
                  {boardPoll.loading ? (
                    <td colSpan={3} className="px-5 py-2.5">
                      <span className="skeleton block h-4 w-full" />
                    </td>
                  ) : (
                    <>
                      <td className="px-3 py-2.5 text-right">{r.used} / {r.total}</td>
                      <td className="px-3 py-2.5 text-right">{r.pct}%</td>
                      <td className="px-5 py-2.5">
                        <span className="meter block" aria-hidden="true">
                          <span style={{ width: `${r.pct}%` }} />
                        </span>
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="text-sub">
                <th scope="row" className="px-5 py-2.5 text-left font-medium">전체</th>
                <td className="px-3 py-2.5 text-right text-ink">{boardPoll.loading ? '–' : `${totalUsed} / ${totalSlots}`}</td>
                <td className="px-3 py-2.5 text-right text-ink">
                  {boardPoll.loading || totalSlots === 0 ? '–' : `${Math.round((totalUsed / totalSlots) * 100)}%`}
                </td>
                <td className="px-5 py-2.5 text-xs">
                  처리 대기 신청{' '}
                  <span className="num text-ink">
                    {queuePoll.data?.supported ? `${queuePoll.data.value.waiting}건` : '알 수 없음'}
                  </span>
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </section>
  );
}
