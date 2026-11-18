import { kstDateKey } from '@/lib/time';
import type { TrackedStatus } from '@/domain/reservation';

/**
 * 하루 예약 한도(프론트 쪽 안내용).
 *
 * 판단은 서버가 한다(AllocationProcessor, 한국 시각 기준 하루). 여기서는 이 브라우저가 아는 번호표로
 * "오늘 몇 시간 썼는지"를 미리 보여 줘서, 한도를 넘는 신청을 보내기 전에 알려 주는 용도다.
 * 다른 기기에서 한 예약은 모르므로 틀릴 수 있고, 그때는 서버가 거절 사유(DAILY_LIMIT)로 알려 준다.
 *
 * 세는 것은 서버 ReservationQueryService.slotsUsedOnDay 와 같다:
 *  - 센다: 접수 중, 확정, 입실, 이용 완료, 노쇼로 해제된 예약
 *  - 안 센다: 거절, 직접 취소
 */
export interface DailyUsage {
  readonly used: number;
  /** 하루에 잡을 수 있는 시간 수. 제한이 없으면 null */
  readonly max: number | null;
}

export interface UsageEntry {
  readonly startsAt: Date;
  readonly status: TrackedStatus | null;
  readonly reasonCode: string | null;
}

export function countsTowardLimit(status: TrackedStatus | null, reasonCode: string | null): boolean {
  switch (status) {
    case 'QUEUED':
    case 'REQUESTED':
    case 'CONFIRMED':
    case 'CHECKED_IN':
    case 'COMPLETED':
      return true;
    case 'CANCELED':
      return reasonCode === 'NO_SHOW';
    case 'REJECTED':
    case null:
      return false;
  }
}

/** dateKey('YYYY-MM-DD', 한국 날짜)의 사용량 */
export function usageOnDay(entries: readonly UsageEntry[], dateKey: string, max: number | null): DailyUsage {
  const used = entries.filter(
    (e) => kstDateKey(e.startsAt) === dateKey && countsTowardLimit(e.status, e.reasonCode),
  ).length;
  return { used, max };
}

export function isLimitReached(usage: DailyUsage | null): boolean {
  return usage !== null && usage.max !== null && usage.used >= usage.max;
}
