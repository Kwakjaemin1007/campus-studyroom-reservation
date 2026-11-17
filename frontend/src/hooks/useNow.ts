import { useEffect, useState } from 'react';

/** 지금 시각(ms)을 일정 간격으로 다시 준다. 카운트다운과 "지난 슬롯" 표시에 쓴다. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}
