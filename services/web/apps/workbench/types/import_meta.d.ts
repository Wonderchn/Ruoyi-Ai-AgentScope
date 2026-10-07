/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Stable declarations; local .env values must never regenerate tracked source. */
  readonly VITE_API_URL?: string;
  readonly VITE_CLIENT_ID?: string;
}

declare interface ImportMeta {
  readonly env: ImportMetaEnv;
}
