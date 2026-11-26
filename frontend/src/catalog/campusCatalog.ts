import type { SlotRef } from '@/domain/reservation';
import { kstSlotStart } from '@/lib/time';

/**
 * 건물·호실·운영 시간 목록.
 *
 * 백엔드에는 호실 마스터가 없다. SlotId 는 "@" 가 없는 아무 roomId 나 받는다.
 * 그래서 목록은 프론트 설정으로 두고, roomId 만 백엔드와 약속한다.
 *
 * 건물 셋은 과제 요구(공학관 K-Cube, 상허기념도서관, 생명과학관)를 따랐다.
 * 호실 이름·개수·정원은 화면을 만들기 위한 예시값이다. 학교가 공개한 자료(2026 재학생 가이드북의
 * "학습공간대여")에는 호실별 목록이 없고, K-Cube 위치로 상허기념도서관 6층·생명과학관 2층 등이 적혀 있다
 * (공학관은 그 목록에 없다). 실제 운영 목록을 받으면 아래 BUILDINGS 만 바꾼다.
 * 한 칸 1시간, 09–22시도 이 시스템의 설계값이다(학교의 실제 K-Cube 는 30분 단위, 학기 중 평일 9:00–17:30).
 */
export interface Room {
  /** 백엔드로 가는 값. "@" 를 쓰면 안 된다 */
  readonly roomId: string;
  readonly name: string;
  readonly capacity: number;
}

export interface Building {
  readonly id: string;
  readonly name: string;
  readonly rooms: readonly Room[];
}

export const OPEN_HOUR = 9;
export const CLOSE_HOUR = 22;
/** 예약 가능 기간: 오늘부터 며칠 뒤 날짜까지. 백엔드 BookingRules.DEFAULT_DAYS_AHEAD 와 같은 값(11/26 결정: 1주일) */
export const DAYS_AHEAD = 7;
/** 이미 시작한 칸도 끝나기 이만큼 전까지는 신청할 수 있다(노쇼로 풀린 자리). 백엔드 BookingRules.LATE_BOOKING_MIN_REMAINING */
export const LATE_BOOKING_MIN_REMAINING_MS = 20 * 60_000;

export const BUILDINGS: readonly Building[] = [
  {
    id: 'eng',
    name: '공학관 K-Cube',
    rooms: [
      { roomId: 'ENG-KC-1', name: '1호', capacity: 4 },
      { roomId: 'ENG-KC-2', name: '2호', capacity: 4 },
      { roomId: 'ENG-KC-3', name: '3호', capacity: 6 },
      { roomId: 'ENG-KC-4', name: '4호', capacity: 8 },
    ],
  },
  {
    id: 'lib',
    name: '상허기념도서관',
    rooms: [
      { roomId: 'LIB-SR-1', name: '스터디룸 1', capacity: 4 },
      { roomId: 'LIB-SR-2', name: '스터디룸 2', capacity: 6 },
      { roomId: 'LIB-SR-3', name: '스터디룸 3', capacity: 6 },
    ],
  },
  {
    id: 'bio',
    name: '생명과학관',
    rooms: [
      { roomId: 'BIO-SR-1', name: '세미나실 1', capacity: 6 },
      { roomId: 'BIO-SR-2', name: '세미나실 2', capacity: 10 },
    ],
  },
];

export function findBuilding(id: string): Building | undefined {
  return BUILDINGS.find((b) => b.id === id);
}

export function findRoom(roomId: string): { building: Building; room: Room } | undefined {
  for (const building of BUILDINGS) {
    const room = building.rooms.find((r) => r.roomId === roomId);
    if (room) return { building, room };
  }
  return undefined;
}

/** 운영 시간의 시작 시(09 ~ 21). 한 칸은 1시간이다. */
export function openHours(): number[] {
  return Array.from({ length: CLOSE_HOUR - OPEN_HOUR }, (_, i) => OPEN_HOUR + i);
}

/** 하루치 슬롯 목록 */
export function slotsOf(room: Room, dateKey: string): SlotRef[] {
  return openHours().map((hour) => ({ roomId: room.roomId, startsAt: kstSlotStart(dateKey, hour) }));
}
