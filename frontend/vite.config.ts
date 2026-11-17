import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import brandPlugin from './src/design/brand/vite-plugin-brand'
import { basePath, GATEWAY_DEFAULTS, resolveBase } from './src/design/lib/links'

/*
 * Base path = the path of this portal's own entry in the link config (src/design/lib/links.ts):
 *   VITE_CANDIDATE_URL unset or "/candidate"  -> /candidate/  (behind the gateway: http://localhost:8088/candidate/)
 *   VITE_CANDIDATE_URL=http://localhost:5173  -> /            (own port: docker compose service candidate-portal)
 * React Router's basename follows import.meta.env.BASE_URL, so routes, assets and links always agree.
 * `pnpm dev` serves http://localhost:5173/candidate/ and proxies /api to the backend (VITE_DEV_API_TARGET, default
 * http://localhost:8080), so the same-origin /api/v1 default works without CORS.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  return {
    base: basePath(resolveBase(env.VITE_CANDIDATE_URL, GATEWAY_DEFAULTS.candidate)),
    plugins: [react(), brandPlugin({ portal: 'candidate' })],
    server: {
      port: 5173,
      proxy: { '/api': env.VITE_DEV_API_TARGET || 'http://localhost:8080' },
    },
  }
})
