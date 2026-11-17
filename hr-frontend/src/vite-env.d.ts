/// <reference types="vite/client" />

/*
 * Build-time settings (Docker build args). Defaults are the single-origin gateway (http://localhost:8088); see
 * src/design/lib/links.ts. Each is a same-origin path ("/hr") or an absolute http(s) URL (per-port mode).
 */
interface ImportMetaEnv {
  /** API base URL including /api/v1. Default "/api/v1" (same origin); per-port mode: http://localhost:8080/api/v1. */
  readonly VITE_API_BASE_URL?: string
  /** Landing site. Default "/"; per-port mode: http://localhost:5170. */
  readonly VITE_SITE_URL?: string
  /** Candidate portal. Default "/candidate"; per-port mode: http://localhost:5173. Its path is that app's Vite base. */
  readonly VITE_CANDIDATE_URL?: string
  /** HR portal. Default "/hr"; per-port mode: http://localhost:5174. Its path is that app's Vite base. */
  readonly VITE_HR_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
