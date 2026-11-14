/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API base URL including `/api/v1`; defaults to http://localhost:8080/api/v1. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
