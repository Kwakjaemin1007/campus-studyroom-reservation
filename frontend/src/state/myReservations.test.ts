import { describe, expect, it } from 'vitest';
import { MyReservationStore, slotOf, type SavedReservation } from './myReservations';

function entry(ticketId: string): SavedReservation {
  return {
    ticketId,
    roomId: 'ENG-KC-1',
    startsAt: '2026-11-18T05:00:00.000Z',
    policyName: 'FCFS',
    bidAmount: 0,
    createdAt: '2026-11-17T00:00:00.000Z',
  };
}

describe('내 예약 번호표 보관함', () => {
  it('사용자별로 나누어 최근 것부터 보관하고, 같은 번호표는 한 번만 둔다', () => {
    const store = new MyReservationStore();
    store.add('a', entry('R-1'));
    store.add('a', entry('R-2'));
    store.add('a', entry('R-1'));
    store.add('b', entry('R-9'));
    expect(store.list('a').map((e) => e.ticketId)).toEqual(['R-1', 'R-2']);
    expect(store.list('b').map((e) => e.ticketId)).toEqual(['R-9']);
    expect(store.list(null)).toEqual([]);
  });

  it('값이 바뀌기 전에는 같은 배열을 돌려준다(useSyncExternalStore 의 조건)', () => {
    const store = new MyReservationStore();
    store.add('a', entry('R-1'));
    const first = store.list('a');
    expect(store.list('a')).toBe(first);
    store.remove('a', 'R-1');
    expect(store.list('a')).not.toBe(first);
    expect(store.list('a')).toEqual([]);
  });

  it('바뀔 때마다 구독자에게 알리고, 구독을 끊으면 알리지 않는다', () => {
    const store = new MyReservationStore();
    let calls = 0;
    const unsubscribe = store.subscribe(() => calls++);
    store.add('a', entry('R-1'));
    store.remove('a', 'R-1');
    unsubscribe();
    store.add('a', entry('R-2'));
    expect(calls).toBe(2);
  });

  it('30건까지만 보관한다', () => {
    const store = new MyReservationStore();
    for (let i = 0; i < 35; i++) store.add('a', entry(`R-${i}`));
    expect(store.list('a')).toHaveLength(30);
    expect(store.list('a')[0]?.ticketId).toBe('R-34');
  });

  it('보관한 값에서 슬롯을 되살린다', () => {
    const slot = slotOf(entry('R-1'));
    expect(slot.roomId).toBe('ENG-KC-1');
    expect(slot.startsAt.toISOString()).toBe('2026-11-18T05:00:00.000Z');
  });
});
