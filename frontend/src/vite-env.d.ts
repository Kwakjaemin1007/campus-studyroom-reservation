/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_GRACE_MINUTES?: string;
  readonly VITE_COLLECT_WINDOW_MINUTES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
