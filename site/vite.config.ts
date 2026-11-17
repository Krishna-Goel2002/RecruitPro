import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import brandPlugin from './src/design/brand/vite-plugin-brand'
import { basePath, GATEWAY_DEFAULTS, resolveBase } from './src/design/lib/links'

// The landing site uses the same brand preset as the portals (src/design/brand/active.ts). The 'candidate' portal
// label is empty, so <title> is the plain brand name.
// Base path = the path of VITE_SITE_URL (src/design/lib/links.ts): "/" both behind the gateway and on its own port.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  return {
    base: basePath(resolveBase(env.VITE_SITE_URL, GATEWAY_DEFAULTS.site)),
    plugins: [react(), brandPlugin({ portal: 'candidate' })],
  }
})
