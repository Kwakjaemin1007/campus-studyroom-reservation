import type { TrackedStatus } from '@/domain/reservation';

/** 예약 진행 단계의 화면 문구와 표식. 색은 상태 의미로만 쓴다. */
export const STATUS_LABEL: Readonly<Record<TrackedStatus, { text: string; chip: string }>> = {
  QUEUED: { text: '접수 대기', chip: 'chip-warn' },
  REQUESTED: { text: '배정 대기', chip: 'chip-warn' },
  CONFIRMED: { text: '확정', chip: 'chip-ok' },
  CHECKED_IN: { text: '입실 완료', chip: 'chip-ok' },
  COMPLETED: { text: '이용 완료', chip: 'chip-neutral' },
  REJECTED: { text: '배정 안 됨', chip: 'chip-danger' },
  CANCELED: { text: '취소됨', chip: 'chip-neutral' },
};
