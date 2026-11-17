import { useState, type ReactNode } from 'react';
import type { TicketWatchEvent } from '@/api';
import type { Building, Room } from '@/catalog/campusCatalog';
import type { Capability } from '@/domain/capability';
import { isApiError, toApiError, type ApiError } from '@/domain/errors';
import type { ActivePolicy, AllocationTiming, SwitchPhase } from '@/domain/policy';
import { isLimitReached, type DailyUsage } from '@/domain/dailyLimit';
import { isFinalStatus, type CreditBalance, type SlotRef, type TicketView } from '@/domain/reservation';
import type { Session } from '@/domain/session';
import { kstDayLabel, kstTime } from '@/lib/time';
import { usePolicyDraft, type AnyPolicyUIModule, type PolicyFormContext } from '@/policy-ui';
import { api } from '@/services';
import type { SavedReservation } from '@/state/myReservations';
import { STATUS_LABEL } from '@/views/shared/statusCopy';
import { ReservationProgress } from './ReservationProgress';

interface Props {
  readonly slot: SlotRef;
  readonly building: Building;
  readonly room: Room;
  readonly session: Session;
  /** 지금 끼워진 정책의 UI 모듈. 레지스트리가 골라 준 것을 그대로 받는다. */
  readonly module: AnyPolicyUIModule;
  readonly policy: ActivePolicy | null;
  readonly timing: AllocationTiming;
  readonly phase: SwitchPhase | null;
  readonly credits: Capability<CreditBalance> | null;
  readonly collectWindowMinutes: number;
  readonly graceMinutes: number;
  readonly now: number;
  /** 이 슬롯이 있는 날(한국 시각)에 내가 쓴 시간. 한도가 없거나 모르면 max 가 null */
  readonly dailyUsage: DailyUsage | null;
  /** 이 슬롯에 걸린 내 신청(있으면 신청서 대신 진행 상황을 보여 준다) */
  readonly existing: SavedReservation | null;
  readonly existingEvent: TicketWatchEvent | null;
  readonly onSubmitted: (ticket: TicketView, bidAmount: number) => void;
  /** 마감(409)을 알게 됐을 때. 시간표를 다시 읽게 한다. */
  readonly onSlotClosed: () => void;
  readonly onCancel: (ticketId: string) => Promise<void>;
  readonly onClose: () => void;
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="shrink-0 text-sm text-sub">{term}</dt>
      <dd className="num m-0 text-right text-sm text-ink">{children}</dd>
    </div>
  );
}

/**
 * 예약 드로어.
 *
 * 이 파일에는 정책 이름으로 갈리는 분기가 없다. 신청서 본문·검증·보낼 값·버튼 문구는 전부
 * props 로 받은 정책 UI 모듈(PolicyUIRegistry 가 고른 것)이 정한다. 정책이 바뀌면 모듈만 바뀐다.
 */
export function BookingDrawer(props: Props) {
  const { slot, module, session } = props;
  const ctx: PolicyFormContext = {
    slot,
    session,
    policy: props.policy,
    credits: module.needs.includes('credits') ? props.credits : null,
    collectWindowMinutes: props.collectWindowMinutes,
  };
  const [draft, setDraft] = usePolicyDraft(module, ctx);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [retryInMs, setRetryInMs] = useState<number | null>(null);
  const [canceling, setCanceling] = useState(false);

  const errors = module.validate(draft, ctx);
  const limitReached = isLimitReached(props.dailyUsage);
  const valid = Object.keys(errors).length === 0 && !limitReached;
  const end = new Date(slot.startsAt.getTime() + 3_600_000);
  const tracking = props.existing !== null;
  const status = props.existingEvent?.status ?? null;

  async function submit() {
    if (!valid || submitting) return;
    setSubmitting(true);
    setError(null);
    setRetryInMs(null);
    const submission = module.toSubmission(draft, ctx);
    try {
      // 연타는 어댑터가 한 요청으로 합치고, 같은 Idempotency-Key 로만 다시 보낸다.
      const ticket = await api.reservations.reserve(
        { slot, bidAmount: submission.bidAmount },
        { onRetry: (delayMs) => setRetryInMs(delayMs) },
      );
      props.onSubmitted(ticket, submission.bidAmount);
    } catch (e) {
      const apiError = toApiError(e);
      setError(apiError);
      if (apiError.kind === 'slot_closed') props.onSlotClosed();
    } finally {
      setSubmitting(false);
      setRetryInMs(null);
    }
  }

  async function cancel() {
    if (!props.existing || canceling) return;
    setCanceling(true);
    setError(null);
    try {
      await props.onCancel(props.existing.ticketId);
    } catch (e) {
      setError(isApiError(e) ? e : toApiError(e));
    } finally {
      setCanceling(false);
    }
  }

  return (
    <aside
      className="fixed inset-0 z-20 flex flex-col bg-white shadow-sm lg:static lg:z-auto lg:w-drawer lg:shrink-0 lg:border-l lg:border-line lg:shadow-none"
      aria-label="예약 신청"
    >
      <header className="hairline-b flex h-14 shrink-0 items-center justify-between px-5">
        <h2 className="m-0 text-md">{tracking ? '내 신청' : '예약 신청'}</h2>
        <button type="button" className="btn-secondary h-8 px-2 text-xs" onClick={props.onClose}>
          닫기
        </button>
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-5">
        <dl className="m-0 flex flex-col gap-2">
          <Fact term="호실">
            {props.building.name} {props.room.name} · {props.room.capacity}인
          </Fact>
          <Fact term="일시">
            {kstDayLabel(slot.startsAt)} {kstTime(slot.startsAt)}–{kstTime(end)}
          </Fact>
          {!tracking && props.dailyUsage && props.dailyUsage.max !== null ? (
            <Fact term="이 날 사용 시간">
              <span className={limitReached ? 'font-semibold text-danger' : undefined}>
                {props.dailyUsage.used} / {props.dailyUsage.max}시간
              </span>
            </Fact>
          ) : null}
          {tracking && status ? (
            <Fact term="상태">
              <span className={STATUS_LABEL[status].chip}>{STATUS_LABEL[status].text}</span>
            </Fact>
          ) : null}
        </dl>

        <hr className="m-0 border-0 border-t border-line" />

        {props.phase && props.phase !== 'ACTIVE' ? (
          <p className="banner-warn m-0">배정 정책을 바꾸는 중입니다. 신청은 받지만 결과가 잠시 늦어질 수 있습니다.</p>
        ) : null}

        {!tracking && limitReached ? (
          <p className="banner-warn m-0" role="status">
            이 날은 하루에 예약할 수 있는 {props.dailyUsage?.max}시간을 이미 사용했습니다. 다른 날을 고르거나 기존 예약을
            취소해 주세요.
          </p>
        ) : null}

        {tracking ? (
          <ReservationProgress
            event={props.existingEvent}
            policyName={props.existing?.policyName ?? null}
            ctx={ctx}
            startsAt={slot.startsAt}
            graceMinutes={props.graceMinutes}
            now={props.now}
          />
        ) : (
          <module.Form draft={draft} onChange={setDraft} errors={errors} ctx={ctx} disabled={submitting} />
        )}

        {retryInMs !== null ? (
          <p className="banner-warn m-0" role="status">
            요청이 많습니다. <span className="num">{Math.max(1, Math.ceil(retryInMs / 1000))}</span>초 뒤 자동으로 다시
            시도합니다.
          </p>
        ) : null}
        {error ? (
          <p className="banner-danger m-0" role="alert">
            {error.userMessage}
          </p>
        ) : null}
      </div>

      <footer className="shrink-0 border-t border-line p-5">
        {tracking ? (
          <div className="flex gap-2">
            {status === 'REQUESTED' || status === 'CONFIRMED' ? (
              <button type="button" className="btn-danger h-11 flex-1" onClick={() => void cancel()} disabled={canceling}>
                {canceling ? '취소하는 중' : status === 'REQUESTED' ? '신청 취소' : '예약 취소'}
              </button>
            ) : null}
            <button type="button" className="btn-secondary h-11 flex-1" onClick={props.onClose}>
              {status && isFinalStatus(status) ? '확인' : '닫기'}
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="btn-primary h-11 w-full text-base"
            onClick={() => void submit()}
            disabled={!valid || submitting}
          >
            {submitting ? '접수하는 중' : module.submitLabel(draft, ctx)}
          </button>
        )}
        {!tracking ? (
          <p className="m-0 mt-2 text-xs text-sub">
            {props.timing === 'IMMEDIATE'
              ? '신청 직후 결과가 이 화면에 표시됩니다.'
              : `접수 마감 뒤(${props.collectWindowMinutes}분 이내) 결과가 정해집니다.`}
          </p>
        ) : null}
      </footer>
    </aside>
  );
}
