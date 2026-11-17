import { useEffect, useRef, useState, type FormEvent } from 'react';
import { hrefOf } from '@/app/useHashRoute';
import { toApiError, type ApiError } from '@/domain/errors';
import type { CheckInOutcome, CheckInResult } from '@/domain/reservation';
import { useNow } from '@/hooks/useNow';
import { kstDayLabel, kstTime } from '@/lib/time';
import { api } from '@/services';

interface OutcomeCopy {
  readonly title: string;
  readonly body: string;
  /** 결과 면의 색. 성공만 포인트 색 틴트를 쓰고 나머지는 상태색이다. */
  readonly panel: string;
  readonly log: string;
}

const OUTCOME: Readonly<Record<CheckInOutcome, OutcomeCopy>> = {
  CHECKED_IN: {
    title: '입실 처리되었습니다',
    body: '예약 시간이 끝나면 퇴실해 주세요.',
    panel: 'border-ku-line bg-ku-tint text-ink',
    log: '입실',
  },
  NOT_ALLOWED: {
    title: '입실할 수 없는 예약입니다',
    body: '이미 입실했거나, 아직 확정되지 않았거나, 취소·해제된 예약입니다.',
    panel: 'border-warn-line bg-warn-tint text-warn',
    log: '입실 불가',
  },
  NOT_FOUND: {
    title: '예약을 찾을 수 없습니다',
    body: '예약 화면의 입실 코드를 다시 확인해 주세요.',
    panel: 'border-warn-line bg-warn-tint text-warn',
    log: '예약 없음',
  },
  INVALID_QR: {
    title: '인식할 수 없는 QR입니다',
    body: '스터디룸 예약 화면의 QR(입실 코드)을 사용해 주세요.',
    panel: 'border-danger-line bg-danger-tint text-danger',
    log: '형식 오류',
  },
};

/** 결과를 이 시간 동안 보여 준 뒤 대기 화면으로 돌아간다 */
const RESULT_HOLD_MS = 8000;
const LOG_SIZE = 5;

/**
 * 호실 입구 태블릿용 QR 입실 화면.
 *
 * 실제 기기는 카메라로 읽은 문자열을 POST /devices/qr/scan 으로 보낸다. 이 화면은 그 문자열을
 * 직접 입력해 스캔을 흉내 낸다. 결과는 서버 응답이 오는 즉시 바뀐다(입실 처리는 서버가 한다).
 * 이 엔드포인트는 기기용이라 로그인 없이 동작하고, 예약의 호실·시간은 알려 주지 않는다.
 */
export function QrKioskView({ initialCode }: { initialCode: string | null }) {
  const now = useNow(1000);
  const [payload, setPayload] = useState(initialCode ?? '');
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<CheckInResult | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [log, setLog] = useState<readonly CheckInResult[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!result) return;
    const id = window.setTimeout(() => setResult(null), RESULT_HOLD_MS);
    return () => window.clearTimeout(id);
  }, [result]);

  async function scan(e: FormEvent) {
    e.preventDefault();
    const value = payload.trim();
    if (!value || scanning) return;
    setScanning(true);
    setError(null);
    try {
      const next = await api.qrDevice.scanRaw(value);
      setResult(next);
      setLog((prev) => [next, ...prev].slice(0, LOG_SIZE));
      setPayload('');
    } catch (err) {
      setResult(null);
      setError(toApiError(err));
    } finally {
      setScanning(false);
      inputRef.current?.focus();
    }
  }

  const today = new Date(now);
  const copy = result ? OUTCOME[result.outcome] : null;

  return (
    <div className="flex min-h-full flex-col bg-canvas">
      <header className="hairline-b flex min-h-[72px] items-center justify-between gap-4 bg-white px-5 lg:px-10">
        <div>
          <p className="m-0 text-xs text-sub">스터디룸</p>
          <h1 className="m-0 text-lg">QR 입실 확인</h1>
        </div>
        <div className="text-right">
          <p className="num m-0 text-xs text-sub">{kstDayLabel(today)}</p>
          <time className="num block text-2xl font-semibold" dateTime={today.toISOString()}>
            {kstTime(today)}
          </time>
        </div>
      </header>

      <main className="grid flex-1 content-start gap-6 px-5 py-6 lg:grid-cols-[380px_minmax(0,1fr)] lg:gap-8 lg:px-10 lg:py-8">
        <section className="flex flex-col gap-4" aria-label="스캔">
          <div className="surface flex h-[260px] items-center justify-center lg:h-[340px]">
            <div className="relative h-[200px] w-[200px] lg:h-[240px] lg:w-[240px]" aria-hidden="true">
              <span className="absolute left-0 top-0 h-9 w-9 border-l-2 border-t-2 border-ink" />
              <span className="absolute right-0 top-0 h-9 w-9 border-r-2 border-t-2 border-ink" />
              <span className="absolute bottom-0 left-0 h-9 w-9 border-b-2 border-l-2 border-ink" />
              <span className="absolute bottom-0 right-0 h-9 w-9 border-b-2 border-r-2 border-ink" />
              <span className="absolute inset-0 flex items-center justify-center px-8 text-center text-md text-sub">
                예약 QR을 비춰 주세요
              </span>
            </div>
          </div>

          <form className="flex flex-col gap-2" onSubmit={(e) => void scan(e)}>
            <label className="field-label mb-0" htmlFor="qr-payload">
              스캔 시뮬레이션 (QR 문자열 직접 입력)
            </label>
            <input
              id="qr-payload"
              ref={inputRef}
              className="field-input h-12 font-mono"
              value={payload}
              onChange={(e) => setPayload(e.target.value)}
              placeholder="RSV:R-…"
              autoComplete="off"
              spellCheck={false}
            />
            <button type="submit" className="btn-primary h-12 text-md" disabled={scanning || payload.trim() === ''}>
              {scanning ? '확인하는 중' : '스캔'}
            </button>
            <p className="m-0 text-xs text-sub">
              실제 기기는 카메라로 읽은 값을 같은 경로로 보냅니다. 입실 코드는 예약 화면의 내 예약에서 볼 수 있습니다.
            </p>
          </form>
        </section>

        <section className="flex min-w-0 flex-col gap-5" aria-label="결과">
          <div aria-live="assertive" aria-atomic="true">
            {error ? (
              <div className="rounded-lg border border-danger-line bg-danger-tint p-6 text-danger">
                <p className="m-0 text-xl font-semibold">확인하지 못했습니다</p>
                <p className="m-0 mt-2 text-md">{error.userMessage} 다시 스캔해 주세요.</p>
              </div>
            ) : copy && result ? (
              <div className={`rounded-lg border p-6 lg:p-8 ${copy.panel}`}>
                <p className="m-0 text-2xl font-semibold">{copy.title}</p>
                <p className="m-0 mt-2 text-md">{copy.body}</p>
                {result.reservationId ? (
                  <p className="m-0 mt-4 break-all font-mono text-xs text-sub">{result.reservationId}</p>
                ) : null}
              </div>
            ) : (
              <div className="surface p-6 lg:p-8">
                <p className="m-0 text-2xl font-semibold">스캔을 기다리는 중입니다</p>
                <p className="m-0 mt-2 text-md text-sub">예약이 확정된 뒤, 시작 후 유예 시간 안에 입실해 주세요.</p>
              </div>
            )}
          </div>

          <div className="surface">
            <h2 className="hairline-b m-0 px-4 py-3 text-base">최근 스캔</h2>
            {log.length === 0 ? (
              <p className="m-0 px-4 py-4 text-sm text-sub">아직 스캔한 기록이 없습니다.</p>
            ) : (
              <table className="w-full border-collapse text-sm">
                <tbody>
                  {log.map((entry, i) => (
                    <tr key={`${entry.at.getTime()}-${i}`} className="hairline-b last:border-b-0">
                      <td className="w-16 whitespace-nowrap px-4 py-2.5 text-sub">{kstTime(entry.at)}</td>
                      <td className="w-24 whitespace-nowrap px-2 py-2.5 text-ink">{OUTCOME[entry.outcome].log}</td>
                      <td className="break-all px-4 py-2.5 font-mono text-xs text-sub">{entry.reservationId ?? '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <a className="self-start text-xs text-sub underline" href={hrefOf('/')}>
            예약 화면으로 돌아가기
          </a>
        </section>
      </main>
    </div>
  );
}
