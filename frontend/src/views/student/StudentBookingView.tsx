import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { BUILDINGS, CLOSE_HOUR, OPEN_HOUR, findRoom } from '@/catalog/campusCatalog';
import { appConfig } from '@/config';
import { slotKey, type SlotRef, type TicketView, type TrackedStatus } from '@/domain/reservation';
import type { Session } from '@/domain/session';
import { useNow } from '@/hooks/useNow';
import { isLimitReached, usageOnDay, type UsageEntry } from '@/domain/dailyLimit';
import { usePolling } from '@/hooks/usePolling';
import { kstDateKey, kstDayLabel, kstSlotStart, shiftDateKey } from '@/lib/time';
import { policyUIRegistry } from '@/policy-ui';
import { api } from '@/services';
import { myReservations, slotOf } from '@/state/myReservations';
import { BookingDrawer } from './BookingDrawer';
import { MyReservations } from './MyReservations';
import { Timetable, TimetableLegend, type BoardState } from './Timetable';
import { useTrackedReservations } from './useTrackedReservations';

/** 오늘부터 며칠 뒤까지 시간표를 볼 수 있는가 */
const DAYS_AHEAD = 6;

const TIMING_COPY = {
  IMMEDIATE: '신청 즉시 배정',
  AT_CLOSE: '접수 마감 시 일괄 배정',
} as const;

/**
 * 학생 예약 화면: 건물 선택 → 호실 × 시간 시간표 → 예약 드로어 → 내 예약.
 *
 * 정책에 따라 달라지는 것(신청서, 필요한 조회, 문구)은 PolicyUIRegistry 가 골라 준 모듈이 정한다.
 * 이 화면은 "지금 정책 이름"을 레지스트리에 넘길 뿐, 정책 이름으로 분기하지 않는다.
 */
export function StudentBookingView({ session }: { session: Session }) {
  const now = useNow(1000);
  const today = kstDateKey(new Date(now));
  const [buildingId, setBuildingId] = useState(BUILDINGS[0]?.id ?? '');
  const [dateKey, setDateKey] = useState(today);
  const [selected, setSelected] = useState<SlotRef | null>(null);

  const building = BUILDINGS.find((b) => b.id === buildingId) ?? BUILDINGS[0];
  const rooms = useMemo(() => building?.rooms ?? [], [building]);
  const lastDay = shiftDateKey(today, DAYS_AHEAD);

  // 날짜가 바뀌어(자정) 보고 있던 날이 과거가 되면 오늘로 옮긴다.
  useEffect(() => {
    if (dateKey < today) setDateKey(today);
  }, [dateKey, today]);

  /* ── 조회 ── */
  const policyPoll = usePolling((signal) => api.policyStatus.getStatus({ signal }), {
    intervalMs: 4000,
    key: session.userId,
  });
  const policyStatus = policyPoll.data?.supported ? policyPoll.data.value : null;
  const policy = policyStatus?.policy ?? null;
  const { module } = policyUIRegistry.resolve(policy?.name);
  const timing = policyStatus?.timing ?? module.timing;
  const collectWindowMinutes = policyStatus?.collectWindowSeconds
    ? Math.max(1, Math.round(policyStatus.collectWindowSeconds / 60))
    : appConfig.collectWindowMinutes;
  const graceMinutes = policyStatus?.graceSeconds
    ? Math.max(1, Math.round(policyStatus.graceSeconds / 60))
    : appConfig.graceMinutes;

  const boardPoll = usePolling(
    (signal) =>
      api.slotBoard.getTaken(
        {
          roomIds: rooms.map((r) => r.roomId),
          from: kstSlotStart(dateKey, OPEN_HOUR),
          to: kstSlotStart(dateKey, CLOSE_HOUR),
        },
        { signal },
      ),
    { intervalMs: 4000, key: `${buildingId}|${dateKey}` },
  );
  const board: BoardState = !boardPoll.data
    ? boardPoll.error
      ? { kind: 'unknown' }
      : { kind: 'loading' }
    : boardPoll.data.supported
      ? { kind: 'ready', taken: boardPoll.data.value }
      : { kind: 'unknown' };

  // 크레딧은 지금 정책의 모듈이 필요하다고 한 때에만 읽는다.
  const needsCredits = module.needs.includes('credits');
  const creditPoll = usePolling((signal) => api.credits.getBalance({ signal }), {
    intervalMs: 8000,
    key: session.userId,
    enabled: needsCredits,
  });
  const credits = needsCredits ? creditPoll.data : null;

  /* ── 내 예약 ── */
  const saved = useSyncExternalStore(
    (onChange) => myReservations.subscribe(onChange),
    () => myReservations.list(session.userId),
  );
  const tracked = useTrackedReservations(saved);

  const mine = useMemo(() => {
    const map = new Map<string, TrackedStatus>();
    for (const entry of saved) {
      const status = tracked.events.get(entry.ticketId)?.status;
      if (status === 'QUEUED' || status === 'REQUESTED' || status === 'CONFIRMED' || status === 'CHECKED_IN') {
        map.set(slotKey(slotOf(entry)), status);
      }
    }
    return map;
  }, [saved, tracked.events]);

  // 내 예약의 단계가 바뀌면 시간표와 크레딧도 바뀌었을 것이므로 다시 읽는다.
  const statusSignature = saved.map((e) => tracked.events.get(e.ticketId)?.status ?? '-').join('|');
  const refreshBoard = boardPoll.refresh;
  const refreshCredits = creditPoll.refresh;
  useEffect(() => {
    refreshBoard();
    refreshCredits();
  }, [statusSignature, refreshBoard, refreshCredits]);

  // 하루 한도 안내용: 이 브라우저가 아는 내 번호표로 날짜별 사용 시간을 센다(판단은 서버).
  const usageEntries: UsageEntry[] = saved.map((e) => {
    const ev = tracked.events.get(e.ticketId);
    return {
      startsAt: new Date(e.startsAt),
      status: ev?.status ?? null,
      reasonCode: ev?.ticket.reasonCode ?? ev?.detail?.reasonCode ?? null,
    };
  });
  const maxPerDay = policyStatus?.maxSlotsPerDay ?? null;
  const usageOfDay = (key: string) => usageOnDay(usageEntries, key, maxPerDay);
  const viewedUsage = usageOfDay(dateKey);

  const selectedKey = selected ? slotKey(selected) : null;
  const [resultTicketId, setResultTicketId] = useState<string | null>(null);
  // 드로어에 보여 줄 번호표: 이 슬롯에 살아 있는 내 신청, 없으면 방금 신청해 결과가 나온 번호표(거절이어도 결과를 남겨 둔다).
  const onSelectedSlot = selectedKey ? saved.filter((e) => slotKey(slotOf(e)) === selectedKey) : [];
  const shown =
    (selectedKey && mine.has(selectedKey)
      ? onSelectedSlot.find((e) => {
          const status = tracked.events.get(e.ticketId)?.status;
          return status !== undefined && status !== 'REJECTED' && status !== 'CANCELED' && status !== 'COMPLETED';
        })
      : undefined) ??
    onSelectedSlot.find((e) => e.ticketId === resultTicketId) ??
    null;

  const select = useCallback((slot: SlotRef) => {
    setResultTicketId(null);
    setSelected(slot);
  }, []);

  const closeDrawer = useCallback(() => {
    setSelected(null);
    setResultTicketId(null);
  }, []);

  function onSubmitted(ticket: TicketView, bidAmount: number) {
    if (!selected) return;
    myReservations.add(session.userId, {
      ticketId: ticket.ticketId,
      roomId: selected.roomId,
      startsAt: selected.startsAt.toISOString(),
      policyName: policy?.name ?? null,
      bidAmount,
      createdAt: new Date().toISOString(),
    });
    setResultTicketId(ticket.ticketId);
    tracked.refresh(ticket.ticketId);
  }

  const cancel = useCallback(
    async (ticketId: string) => {
      await api.reservations.cancel(ticketId);
      tracked.refresh(ticketId);
    },
    [tracked],
  );

  const forget = useCallback(
    (ticketId: string) => myReservations.remove(session.userId, ticketId),
    [session.userId],
  );

  const selectedPlace = selected ? findRoom(selected.roomId) : undefined;
  const policyLabel = policy ? policyUIRegistry.labelOf(policy.name) : null;

  return (
    <div className="flex min-h-0 flex-1">
      <main className="flex min-w-0 flex-1 flex-col gap-5 px-4 py-5 lg:px-8">
        <div className="hairline-b flex gap-5 overflow-x-auto" role="tablist" aria-label="건물">
          {BUILDINGS.map((b) => {
            const active = b.id === building?.id;
            return (
              <button
                key={b.id}
                type="button"
                role="tab"
                aria-selected={active}
                className={`-mb-px whitespace-nowrap border-0 border-b-2 bg-transparent px-0.5 pb-2.5 pt-1 text-base ${
                  active ? 'border-ku font-semibold text-ink' : 'border-transparent text-sub hover:text-ink'
                }`}
                onClick={() => {
                  setBuildingId(b.id);
                  closeDrawer();
                }}
              >
                {b.name}
              </button>
            );
          })}
        </div>

        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <button
            type="button"
            className="btn-secondary"
            onClick={() => {
              setDateKey(shiftDateKey(dateKey, -1));
              closeDrawer();
            }}
            disabled={dateKey <= today}
          >
            이전 날
          </button>
          <div className="num min-w-[112px] text-center text-md font-semibold" aria-live="polite">
            {kstDayLabel(kstSlotStart(dateKey, 12))}
          </div>
          <button
            type="button"
            className="btn-secondary"
            onClick={() => {
              setDateKey(shiftDateKey(dateKey, 1));
              closeDrawer();
            }}
            disabled={dateKey >= lastDay}
          >
            다음 날
          </button>

          <div className="ml-auto flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-sub">
            {needsCredits && credits?.supported ? (
              <span>
                보유 크레딧 <span className="num font-semibold text-ink">{credits.value.available}</span>
                {credits.value.held > 0 ? <span className="num"> · 입찰 중 {credits.value.held}</span> : null}
                {credits.value.renewsAt ? (
                  <span className="num"> · {kstDateKey(credits.value.renewsAt)} 갱신</span>
                ) : null}
              </span>
            ) : null}
            {maxPerDay !== null ? (
              <span>
                이 날 사용 시간{' '}
                <span className={`num font-semibold ${isLimitReached(viewedUsage) ? 'text-danger' : 'text-ink'}`}>
                  {viewedUsage.used} / {maxPerDay}시간
                </span>
              </span>
            ) : null}
            <span className="rounded-md border border-line bg-white px-2.5 py-1 text-xs">
              현재 배정 정책{' '}
              {policyPoll.loading ? (
                <span className="skeleton inline-block h-3 w-16 align-middle" aria-label="불러오는 중" />
              ) : policyLabel ? (
                <>
                  <span className="font-semibold text-ink">{policyLabel}</span> · {TIMING_COPY[timing]}
                </>
              ) : (
                <span className="text-ink">확인할 수 없음</span>
              )}
            </span>
          </div>
        </div>

        {policyStatus?.phase && policyStatus.phase !== 'ACTIVE' ? (
          <p className="banner-warn m-0" role="status">
            배정 정책을 바꾸는 중입니다. 신청은 받지만 결과가 잠시 늦어질 수 있습니다.
          </p>
        ) : null}
        {boardPoll.error ? (
          <div className="banner-danger items-center justify-between" role="alert">
            <span>
              <strong className="font-semibold">시간표를 불러오지 못했습니다.</strong> {boardPoll.error.userMessage}
            </span>
            <button type="button" className="btn-secondary h-8 shrink-0 px-2 text-xs" onClick={boardPoll.refresh}>
              다시 시도
            </button>
          </div>
        ) : null}
        {board.kind === 'unknown' && !boardPoll.error ? (
          <p className="banner-neutral m-0">
            이 서버는 마감 현황을 알려 주지 않습니다. 시간을 골라 신청하면 마감 여부를 바로 알려 드립니다.
          </p>
        ) : null}

        <Timetable
          rooms={rooms}
          dateKey={dateKey}
          board={board}
          mine={mine}
          selectedKey={selectedKey}
          now={now}
          onSelect={select}
        />
        <TimetableLegend showTaken />

        <MyReservations
          saved={saved}
          events={tracked.events}
          session={session}
          collectWindowMinutes={collectWindowMinutes}
          graceMinutes={graceMinutes}
          now={now}
          onCancel={cancel}
          onForget={forget}
        />
      </main>

      {selected && selectedPlace ? (
        <BookingDrawer
          key={`${selectedKey}|${shown?.ticketId ?? 'new'}`}
          slot={selected}
          building={selectedPlace.building}
          room={selectedPlace.room}
          session={session}
          module={module}
          policy={policy}
          timing={timing}
          phase={policyStatus?.phase ?? null}
          credits={credits}
          collectWindowMinutes={collectWindowMinutes}
          graceMinutes={graceMinutes}
          now={now}
          dailyUsage={usageOfDay(kstDateKey(selected.startsAt))}
          minPartySize={policyStatus?.minPartySize ?? null}
          existing={shown}
          existingEvent={shown ? (tracked.events.get(shown.ticketId) ?? null) : null}
          onSubmitted={onSubmitted}
          onSlotClosed={boardPoll.refresh}
          onPolicyChanged={policyPoll.refresh}
          onCancel={cancel}
          onClose={closeDrawer}
        />
      ) : null}
    </div>
  );
}
