/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_GRACE_MINUTES?: string;
  readonly VITE_COLLECT_WINDOW_MINUTES?: string;
  /** '1' 이면 데모 빌드(브라우저 안 데모 서버) */
  readonly VITE_DEMO?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
