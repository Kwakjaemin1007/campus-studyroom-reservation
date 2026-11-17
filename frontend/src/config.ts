/**
 * 실행 설정. .env 로 바꾼다(.env.example 참고).
 * 유예 시간과 접수 창 길이는 서버가 GET /policy 로 내려 준다(graceSeconds, collectWindowSeconds).
 * 아래 두 값은 그 조회가 없는 예전 서버에 붙었을 때만 쓰는 대체값이다.
 */
function positiveNumber(raw: string | undefined, fallback: number): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

const env = import.meta.env;

export const appConfig = {
  apiBase: env.VITE_API_BASE ?? '/api',
  /**
   * 데모 빌드(VITE_DEMO=1): Java 서버 대신 브라우저 안의 데모 서버(src/demo)에 붙는다.
   * 화면·어댑터 코드는 같고, fetch 만 바뀐다.
   */
  demo: env.VITE_DEMO === '1',
  /** 시작 후 이 시간 안에 입실하지 않으면 노쇼로 해제된다 */
  graceMinutes: positiveNumber(env.VITE_GRACE_MINUTES, 10),
  /** 마감형 정책에서 첫 요청 뒤 접수 창이 열려 있는 시간 */
  collectWindowMinutes: positiveNumber(env.VITE_COLLECT_WINDOW_MINUTES, 10),
} as const;
