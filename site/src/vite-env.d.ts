/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Candidate portal origin (calls to action "Find jobs", "Create an account"); default http://localhost:5173. */
  readonly VITE_CANDIDATE_URL?: string
  /** HR portal origin (calls to action for companies); default http://localhost:5174. */
  readonly VITE_HR_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
