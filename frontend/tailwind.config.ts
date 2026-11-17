import type { Config } from 'tailwindcss';

/**
 * 디자인 토큰.
 *
 * theme 를 extend 하지 않고 통째로 바꾼다. 팔레트·곡률·그림자에 없는 값은 유틸리티 자체가 생기지 않으므로
 * rounded-2xl, shadow-lg, bg-emerald-500 같은 클래스는 써도 아무 효과가 없다(금지 규칙을 설정으로 강제).
 *
 * 색 비중: 무채색 면 90% · 슬레이트 글자 8% · KU 그린 2%.
 * KU 그린은 핵심 실행 버튼, 선택된 타임슬롯, 활성 탭 표시에만 쓴다.
 */
const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  corePlugins: {
    // 그라데이션과 글래스모피즘은 쓰지 않는다.
    backgroundImage: false,
    gradientColorStops: false,
    backdropBlur: false,
    backdropBrightness: false,
    backdropContrast: false,
    backdropFilter: false,
    backdropGrayscale: false,
    backdropHueRotate: false,
    backdropInvert: false,
    backdropOpacity: false,
    backdropSaturate: false,
    backdropSepia: false,
  },
  theme: {
    colors: {
      transparent: 'transparent',
      current: 'currentColor',
      white: '#FFFFFF',
      // 면
      canvas: '#F8FAFC',
      // 1px 헤어라인. strong 은 입력 테두리처럼 조금 더 보여야 하는 선에만 쓴다.
      line: { DEFAULT: '#E2E8F0', strong: '#CBD5E1' },
      // 글자
      ink: '#0F172A',
      sub: '#475569',
      // 비활성·플레이스홀더 전용. 본문에는 쓰지 않는다(대비 부족).
      mute: '#94A3B8',
      // 포인트(2%)
      ku: {
        DEFAULT: '#036B3F',
        hover: '#025230',
        tint: '#ECFDF5',
        line: '#A7F3D0',
      },
      // 상태색. 포인트 색과 역할이 다르다: 거절·오류 / 대기·주의.
      danger: { DEFAULT: '#B42318', tint: '#FEF3F2', line: '#FECDCA' },
      warn: { DEFAULT: '#B54708', tint: '#FFFAEB', line: '#FEDF89' },
    },
    borderRadius: {
      none: '0',
      md: '6px',
      lg: '8px',
    },
    boxShadow: {
      none: 'none',
      // 모달·드로어 전용.
      sm: '0 1px 2px 0 rgb(15 23 42 / 0.06)',
    },
    fontFamily: {
      // Inter 를 앞에 둔다: 영문·숫자는 Inter 로, Inter 에 없는 한글은 다음 순서인 Pretendard 로 그려진다.
      sans: [
        '"Inter Variable"',
        '"Pretendard Variable"',
        'Pretendard',
        '-apple-system',
        'BlinkMacSystemFont',
        '"Apple SD Gothic Neo"',
        '"Malgun Gothic"',
        'system-ui',
        'sans-serif',
      ],
      mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
    },
    fontSize: {
      // 유틸리티 앱 밀도. [크기, 줄 높이]
      '2xs': ['11px', '16px'],
      xs: ['12px', '18px'],
      sm: ['13px', '20px'],
      base: ['14px', '22px'],
      md: ['16px', '24px'],
      lg: ['18px', '26px'],
      xl: ['22px', '30px'],
      // 키오스크 전용 큰 글자
      '2xl': ['28px', '36px'],
      '3xl': ['40px', '48px'],
    },
    extend: {
      spacing: {
        // 타임테이블 칸 높이, 드로어 폭
        cell: '36px',
        drawer: '400px',
      },
      keyframes: {
        'skeleton-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.55' },
        },
      },
      animation: {
        skeleton: 'skeleton-pulse 1.4s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};

export default config;
