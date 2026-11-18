import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// 백엔드(app 모듈)에는 CORS 설정이 없다. 그래서 개발 서버가 /api 요청을 8080으로 대신 보내 준다.
// 브라우저 입장에서는 같은 출처라 CORS가 필요 없다. 운영에서도 같은 출처(/api)로 배포하는 것을 전제로 한다.
const BACKEND = process.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8080';

const proxy = {
  '/api': {
    target: BACKEND,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ''),
  },
};

export default defineConfig({
  // 데모 빌드는 정적 호스팅(하위 경로 포함)에서 열리도록 상대 경로로 만든다.
  base: process.env.VITE_DEMO === '1' ? './' : '/',
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  // 팀 공유용 임시 터널(cloudflared) 주소를 허용한다. Vite는 모르는 Host 헤더를 기본으로 막는다.
  server: { port: 5173, proxy, allowedHosts: ['.trycloudflare.com'] },
  preview: { port: 4173, proxy, allowedHosts: ['.trycloudflare.com'] },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
