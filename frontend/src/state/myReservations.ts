import type { SlotRef } from '@/domain/reservation';

/**
 * 이 브라우저에서 신청한 예약의 번호표 목록.
 *
 * 백엔드에는 "내 예약 목록" 조회가 없다(번호표 id 로 한 건씩만 읽을 수 있다).
 * 그래서 신청할 때 받은 번호표를 브라우저에 적어 두고, 화면은 그 번호표들을 하나씩 조회한다.
 * 다른 기기에서 신청한 예약은 보이지 않는다. 목록 조회 API 가 생기면 이 파일만 바꾼다.
 */
export interface SavedReservation {
  readonly ticketId: string;
  readonly roomId: string;
  /** ISO-8601 */
  readonly startsAt: string;
  /** 신청할 때의 정책. 알 수 없었으면 null */
  readonly policyName: string | null;
  readonly bidAmount: number;
  /** ISO-8601 */
  readonly createdAt: string;
}

export function slotOf(saved: SavedReservation): SlotRef {
  return { roomId: saved.roomId, startsAt: new Date(saved.startsAt) };
}

const KEY_PREFIX = 'studyroom.tickets.v1.';
const MAX_ENTRIES = 30;
const EMPTY: readonly SavedReservation[] = [];

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

function isSaved(value: unknown): value is SavedReservation {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return typeof v.ticketId === 'string' && typeof v.roomId === 'string' && typeof v.startsAt === 'string';
}

export class MyReservationStore {
  private readonly cache = new Map<string, readonly SavedReservation[]>();
  private readonly listeners = new Set<() => void>();

  /** 최근에 신청한 것부터. 같은 userId 에는 값이 바뀌기 전까지 같은 배열을 돌려준다. */
  list(userId: string | null): readonly SavedReservation[] {
    if (!userId) return EMPTY;
    const cached = this.cache.get(userId);
    if (cached) return cached;
    let parsed: readonly SavedReservation[] = EMPTY;
    try {
      const raw = storage()?.getItem(KEY_PREFIX + userId);
      const value: unknown = raw ? JSON.parse(raw) : [];
      if (Array.isArray(value)) parsed = value.filter(isSaved);
    } catch {
      parsed = EMPTY;
    }
    this.cache.set(userId, parsed);
    return parsed;
  }

  add(userId: string, entry: SavedReservation): void {
    const rest = this.list(userId).filter((e) => e.ticketId !== entry.ticketId);
    this.write(userId, [entry, ...rest].slice(0, MAX_ENTRIES));
  }

  remove(userId: string, ticketId: string): void {
    this.write(userId, this.list(userId).filter((e) => e.ticketId !== ticketId));
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private write(userId: string, entries: readonly SavedReservation[]): void {
    this.cache.set(userId, entries);
    try {
      storage()?.setItem(KEY_PREFIX + userId, JSON.stringify(entries));
    } catch {
      // 저장소를 못 쓰면 이 탭이 열려 있는 동안만 기억한다.
    }
    for (const l of this.listeners) l();
  }
}

export const myReservations = new MyReservationStore();
