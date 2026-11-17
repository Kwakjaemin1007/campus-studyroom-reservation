import { useState } from 'react';
import type { TicketWatchEvent } from '@/api';
import { hrefOf } from '@/app/useHashRoute';
import { toQrPayload } from '@/api/adapters/qrDeviceAdapter';
import { findRoom } from '@/catalog/campusCatalog';
import { toApiError, type ApiError } from '@/domain/errors';
import { isFinalStatus } from '@/domain/reservation';
import type { Session } from '@/domain/session';
import { kstDayLabel, kstTime } from '@/lib/time';
import type { PolicyFormContext } from '@/policy-ui';
import { slotOf, type SavedReservation } from '@/state/myReservations';
import { STATUS_LABEL } from '@/views/shared/statusCopy';
import { ReservationProgress } from './ReservationProgress';

interface ListProps {
  readonly saved: readonly SavedReservation[];
  readonly events: ReadonlyMap<string, TicketWatchEvent>;
  readonly session: Session;
  readonly collectWindowMinutes: number;
  readonly graceMinutes: number;
  readonly now: number;
  readonly onCancel: (ticketId: string) => Promise<void>;
  readonly onForget: (ticketId: string) => void;
}

/** 내 예약 목록. 이 브라우저에서 신청한 번호표를 최근 것부터 보여 준다. */
export function MyReservations(props: ListProps) {
  return (
    <section aria-labelledby="my-reservations-title" className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-3">
        <h2 id="my-reservations-title" className="m-0 text-md">
          내 예약
        </h2>
        <span className="text-xs text-sub">이 브라우저에서 신청한 예약만 보입니다.</span>
      </div>
      {props.saved.length === 0 ? (
        <p className="surface m-0 px-4 py-5 text-sm text-sub">
          진행 중인 예약이 없습니다. 시간표에서 빈 칸을 눌러 신청해 주세요.
        </p>
      ) : (
        <ul className="m-0 grid list-none gap-3 p-0 xl:grid-cols-2">
          {props.saved.map((entry) => (
            <li key={entry.ticketId}>
              <ReservationCard entry={entry} event={props.events.get(entry.ticketId) ?? null} {...props} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface CardProps extends Omit<ListProps, 'saved' | 'events'> {
  readonly entry: SavedReservation;
  readonly event: TicketWatchEvent | null;
}

function ReservationCard({ entry, event, session, now, onCancel, onForget, ...rest }: CardProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [showCode, setShowCode] = useState(false);

  const slot = slotOf(entry);
  const place = findRoom(entry.roomId);
  const end = new Date(slot.startsAt.getTime() + 3_600_000);
  const status = event?.status ?? null;
  const cancelable = status === 'REQUESTED' || status === 'CONFIRMED';
  const removable = (status !== null && isFinalStatus(status)) || event?.timedOut === true;
  const payload = toQrPayload(entry.ticketId);

  const ctx: PolicyFormContext = {
    slot,
    session,
    policy: null,
    credits: null,
    collectWindowMinutes: rest.collectWindowMinutes,
  };

  async function cancel() {
    setBusy(true);
    setError(null);
    try {
      await onCancel(entry.ticketId);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="surface flex flex-col gap-3 p-4">
      <header className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="m-0 text-base">
            {place ? `${place.building.name} ${place.room.name}` : entry.roomId}
          </h3>
          <p className="num m-0 text-sm text-sub">
            {kstDayLabel(slot.startsAt)} {kstTime(slot.startsAt)}–{kstTime(end)}
          </p>
        </div>
        {status ? (
          <span className={`${STATUS_LABEL[status].chip} shrink-0`}>{STATUS_LABEL[status].text}</span>
        ) : (
          <span className="skeleton h-5 w-12 shrink-0" aria-label="상태를 불러오는 중" />
        )}
      </header>

      <ReservationProgress
        event={event}
        policyName={entry.policyName}
        ctx={ctx}
        startsAt={slot.startsAt}
        graceMinutes={rest.graceMinutes}
        now={now}
      />

      {showCode ? (
        <div className="rounded-md border border-line bg-canvas px-3 py-2">
          <p className="m-0 text-xs text-sub">입실 코드 (현장 QR 기기가 읽는 값)</p>
          <p className="m-0 break-all font-mono text-xs text-ink">{payload}</p>
          <a className="text-xs text-ink underline" href={hrefOf('/kiosk', { code: payload })}>
            키오스크 화면에서 이 코드로 열기
          </a>
        </div>
      ) : null}

      {error ? (
        <p className="banner-danger m-0" role="alert">
          {error.userMessage}
        </p>
      ) : null}

      {cancelable || removable ? (
        <footer className="flex flex-wrap gap-2">
          {status === 'CONFIRMED' ? (
            <button type="button" className="btn-secondary" onClick={() => setShowCode((v) => !v)} aria-expanded={showCode}>
              {showCode ? '입실 코드 숨기기' : '입실 코드 보기'}
            </button>
          ) : null}
          {cancelable ? (
            <button type="button" className="btn-danger" onClick={() => void cancel()} disabled={busy}>
              {busy ? '취소하는 중' : status === 'REQUESTED' ? '신청 취소' : '예약 취소'}
            </button>
          ) : null}
          {removable ? (
            <button type="button" className="btn-secondary" onClick={() => onForget(entry.ticketId)}>
              목록에서 지우기
            </button>
          ) : null}
        </footer>
      ) : null}
    </article>
  );
}
