import type { ReactNode } from 'react';
import type { TicketWatchEvent } from '@/api';
import type { TrackedStatus } from '@/domain/reservation';
import { formatCountdown, formatElapsed, formatRemaining, kstTime } from '@/lib/time';
import { policyUIRegistry, type PolicyFormContext } from '@/policy-ui';

interface Props {
  /** 아직 한 번도 못 읽었으면 null */
  readonly event: TicketWatchEvent | null;
  /** 신청할 때의 정책 이름. 모르면 null */
  readonly policyName: string | null;
  readonly ctx: PolicyFormContext;
  readonly startsAt: Date;
  readonly graceMinutes: number;
  readonly now: number;
}

/**
 * 확정된 예약의 입실 마감 표시.
 * 시작 전에는 막대가 가득 차 있고, 시작하면 유예 시간(기본 10분) 동안 줄어든다.
 * 유예가 끝나면 서버가 노쇼로 해제한다(지연 메시지). 화면의 타이머는 안내일 뿐 판단은 서버가 한다.
 */
export function CheckInCountdown({ startsAt, deadline, now }: { startsAt: Date; deadline: Date; now: number }) {
  const total = Math.max(1, deadline.getTime() - startsAt.getTime());
  const remaining = deadline.getTime() - now;
  const started = now >= startsAt.getTime();
  const ratio = started ? Math.min(1, Math.max(0, remaining / total)) : 1;
  const graceMinutes = Math.round(total / 60_000);

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3 text-xs text-sub">
        <span>{started ? '입실 마감까지' : '시작까지'}</span>
        <output className="num text-sm font-semibold text-ink" aria-live="off">
          {started ? formatCountdown(remaining) : formatRemaining(startsAt.getTime() - now)}
        </output>
      </div>
      <div
        className="meter"
        role="progressbar"
        aria-label="입실 마감까지 남은 시간"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(ratio * 100)}
      >
        <span style={{ width: `${ratio * 100}%` }} />
      </div>
      <p className="m-0 text-xs text-sub">
        {remaining <= 0
          ? '입실 마감 시간이 지났습니다. 입실하지 않은 예약은 곧 자동으로 해제됩니다.'
          : started
            ? `${kstTime(deadline)}까지 현장 QR로 입실하지 않으면 자동으로 해제됩니다.`
            : `시작 후 ${graceMinutes}분(${kstTime(deadline)}) 안에 현장 QR로 입실하지 않으면 자동으로 해제됩니다.`}
      </p>
    </div>
  );
}

/**
 * 접수 창이 닫혀 결과가 나올 때까지 남은 시간. 접수 창은 그 시간의 첫 신청부터 열리므로
 * 실제 결과는 이보다 빨리 나올 수 있다. 그래서 "최대"로 보여 준다.
 */
function CollectCountdown({ from, latest, now }: { from: Date; latest: Date; now: number }) {
  const total = Math.max(1, latest.getTime() - from.getTime());
  const remaining = latest.getTime() - now;
  const ratio = Math.min(1, Math.max(0, remaining / total));
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3 text-xs text-sub">
        <span>결과가 나오기까지 최대</span>
        <output className="num text-sm font-semibold text-ink" aria-live="off">
          {remaining > 0 ? formatRemaining(remaining) : '곧 확정'}
        </output>
      </div>
      <div
        className="meter"
        role="progressbar"
        aria-label="접수 마감까지 남은 시간"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(ratio * 100)}
      >
        <span style={{ width: `${ratio * 100}%` }} />
      </div>
    </div>
  );
}

function Row({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="text-xs text-sub">{term}</dt>
      <dd className="num m-0 text-sm text-ink">{children}</dd>
    </div>
  );
}

/**
 * 번호표 한 장의 진행 상황. 예약 드로어와 내 예약 카드가 같이 쓴다.
 * 대기 문구와 거절 사유 문구는 그 예약을 받은 정책의 UI 모듈에서 가져온다.
 */
export function ReservationProgress({ event, policyName, ctx, startsAt, graceMinutes, now }: Props) {
  if (!event) {
    return (
      <div className="flex flex-col gap-2" aria-busy="true" aria-label="예약 상태를 불러오는 중">
        <span className="skeleton block h-4 w-40" />
        <span className="skeleton block h-4 w-56" />
      </div>
    );
  }

  const status: TrackedStatus = event.status;
  const detail = event.detail;
  const { module } = policyUIRegistry.resolve(detail?.policyName ?? policyName);
  const staleNote = event.stale ? (
    <p className="m-0 text-xs text-warn">마지막 조회에 실패했습니다. 자동으로 다시 확인하는 중입니다.</p>
  ) : null;

  if (event.timedOut) {
    return (
      <p className="banner-warn m-0">
        서버에서 이 신청의 처리 결과를 확인하지 못했습니다. 서버가 다시 시작되었을 수 있습니다. 시간표를 확인하고 필요하면
        다시 신청해 주세요.
      </p>
    );
  }

  if (status === 'QUEUED' || status === 'REQUESTED') {
    const copy = module.pendingCopy(ctx);
    // 접수 창은 그 시간의 첫 신청부터 열린다. 내 신청이 첫 신청이 아닐 수 있으므로 "늦어도"로만 말한다.
    const latest =
      status === 'REQUESTED' && detail?.requestedAt
        ? new Date(Math.min(detail.requestedAt.getTime() + ctx.collectWindowMinutes * 60_000, startsAt.getTime()))
        : null;
    return (
      <div className="flex flex-col gap-3">
        <div className="banner-warn flex-col gap-1">
          <strong className="font-semibold">{copy.title}</strong>
          <span>{copy.body}</span>
        </div>
        <dl className="m-0 flex flex-col gap-1.5">
          {status === 'QUEUED' && event.queue ? (
            <Row term="처리를 기다리는 전체 신청">{event.queue.waiting}건</Row>
          ) : null}
          {status === 'QUEUED' ? <Row term="기다린 시간">{formatElapsed(event.waitedMs)}</Row> : null}
          {latest ? <Row term="결과 확정">늦어도 {kstTime(latest)}</Row> : null}
          {detail && detail.bidAmount > 0 ? <Row term="입찰액 (묶인 크레딧)">{detail.bidAmount}</Row> : null}
        </dl>
        {latest && detail?.requestedAt ? (
          <CollectCountdown from={detail.requestedAt} latest={latest} now={now} />
        ) : null}
        {staleNote}
      </div>
    );
  }

  if (status === 'CONFIRMED') {
    const deadline = detail?.checkInDeadline ?? new Date(startsAt.getTime() + graceMinutes * 60_000);
    return (
      <div className="flex flex-col gap-3">
        {detail && detail.chargedAmount > 0 ? (
          <dl className="m-0">
            <Row term="차감된 크레딧">{detail.chargedAmount}</Row>
          </dl>
        ) : null}
        <CheckInCountdown startsAt={startsAt} deadline={deadline} now={now} />
        {staleNote}
      </div>
    );
  }

  if (status === 'CHECKED_IN') return <p className="banner-ok m-0">입실 처리되었습니다.</p>;
  if (status === 'COMPLETED') return <p className="banner-neutral m-0">이용이 끝난 예약입니다.</p>;

  const reason = policyUIRegistry.reasonText(event.ticket.reasonCode ?? detail?.reasonCode ?? null, detail?.policyName ?? policyName);
  if (status === 'REJECTED') {
    return <p className="banner-danger m-0">{reason || '이 시간에 배정되지 않았습니다.'}</p>;
  }
  return <p className="banner-neutral m-0">{reason || '취소된 예약입니다.'}</p>;
}
