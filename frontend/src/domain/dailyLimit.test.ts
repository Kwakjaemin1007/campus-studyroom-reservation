import { describe, expect, it } from 'vitest';
import { countsTowardLimit, isLimitReached, usageOnDay, type UsageEntry } from './dailyLimit';
import { formatRemaining } from '@/lib/time';

const at = (iso: string) => new Date(iso);

describe('하루 한도 계산 (화면 안내용)', () => {
  it('접수 중·확정·입실·완료·노쇼는 세고, 거절과 직접 취소는 세지 않는다', () => {
    expect(countsTowardLimit('QUEUED', null)).toBe(true);
    expect(countsTowardLimit('REQUESTED', null)).toBe(true);
    expect(countsTowardLimit('CONFIRMED', null)).toBe(true);
    expect(countsTowardLimit('CHECKED_IN', null)).toBe(true);
    expect(countsTowardLimit('COMPLETED', null)).toBe(true);
    expect(countsTowardLimit('CANCELED', 'NO_SHOW')).toBe(true);
    expect(countsTowardLimit('CANCELED', 'USER_CANCELED')).toBe(false);
    expect(countsTowardLimit('REJECTED', 'OUTBID')).toBe(false);
    expect(countsTowardLimit(null, null)).toBe(false);
  });

  it('하루는 한국 시각 기준이다 (UTC 14:00 은 한국 23:00, 15:00 은 다음 날 00:00)', () => {
    const entries: UsageEntry[] = [
      { startsAt: at('2026-11-18T14:00:00Z'), status: 'CONFIRMED', reasonCode: null },
      { startsAt: at('2026-11-18T15:00:00Z'), status: 'CONFIRMED', reasonCode: null },
      { startsAt: at('2026-11-18T01:00:00Z'), status: 'REJECTED', reasonCode: 'DAILY_LIMIT' },
    ];
    expect(usageOnDay(entries, '2026-11-18', 2)).toEqual({ used: 1, max: 2 });
    expect(usageOnDay(entries, '2026-11-19', 2)).toEqual({ used: 1, max: 2 });
  });

  it('한도에 닿았는지', () => {
    expect(isLimitReached({ used: 2, max: 2 })).toBe(true);
    expect(isLimitReached({ used: 1, max: 2 })).toBe(false);
    expect(isLimitReached({ used: 9, max: null })).toBe(false);
    expect(isLimitReached(null)).toBe(false);
  });
});

describe('남은 시간 표시', () => {
  it('길이에 따라 형식이 바뀐다', () => {
    expect(formatRemaining(12 * 60_000 + 45_000)).toBe('12:45');
    expect(formatRemaining(3 * 3_600_000 + 12 * 60_000 + 45_000)).toBe('3:12:45');
    expect(formatRemaining(2 * 86_400_000 + 3 * 3_600_000 + 12 * 60_000 + 5_000)).toBe('2일 03:12:05');
    expect(formatRemaining(-5000)).toBe('00:00');
    expect(formatRemaining(1)).toBe('00:01');
  });
});
