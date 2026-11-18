/**
 * 시간 처리. 화면은 한국 시간(KST, UTC+9)으로 보여 주고, 백엔드에는 UTC 시각(ISO-8601)으로 보낸다.
 * 한국은 서머타임이 없어 +09:00 고정으로 계산해도 된다.
 */
const KST = 'Asia/Seoul';

const dateFmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: KST,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

const timeFmt = new Intl.DateTimeFormat('ko-KR', {
  timeZone: KST,
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

const dayLabelFmt = new Intl.DateTimeFormat('ko-KR', {
  timeZone: KST,
  month: 'numeric',
  day: 'numeric',
  weekday: 'short',
});

/** 'YYYY-MM-DD' (KST 기준 날짜) */
export function kstDateKey(at: Date): string {
  return dateFmt.format(at);
}

/** 'HH:mm' (KST) */
export function kstTime(at: Date): string {
  return timeFmt.format(at);
}

/** '11. 20. (금)' 같은 날짜 표시 (KST) */
export function kstDayLabel(at: Date): string {
  return dayLabelFmt.format(at);
}

/** KST 날짜와 시(0~23)로 슬롯 시작 시각을 만든다. */
export function kstSlotStart(dateKey: string, hour: number): Date {
  const hh = String(hour).padStart(2, '0');
  return new Date(`${dateKey}T${hh}:00:00+09:00`);
}

/** dateKey 에서 days 만큼 옮긴 날짜 키 */
export function shiftDateKey(dateKey: string, days: number): string {
  const noon = new Date(`${dateKey}T12:00:00+09:00`);
  return kstDateKey(new Date(noon.getTime() + days * 86_400_000));
}

/** 남은 시간을 'mm:ss' 로. 음수는 00:00 */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * 시작까지 남은 시간처럼 길 수도 있는 카운트다운. 1초마다 줄어든다.
 * 1일 이상: '2일 03:12:45', 1시간 이상: '3:12:45', 그 미만: '12:45'. 음수는 '00:00'.
 */
export function formatRemaining(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const d = Math.floor(total / 86_400);
  const h = Math.floor((total % 86_400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  if (d > 0) return `${d}일 ${String(h).padStart(2, '0')}:${mm}:${ss}`;
  if (h > 0) return `${h}:${mm}:${ss}`;
  return `${mm}:${ss}`;
}

/** 기다린 시간을 사람 말로. 예: '12초', '3분 05초' */
export function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return `${total}초`;
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}분 ${String(s).padStart(2, '0')}초`;
}
