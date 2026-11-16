import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/** Component tests (jsdom). The brand plugin is build-only (index.html, token injection), so it is left out. */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
    unstubEnvs: true,
    testTimeout: 15_000,
  },
})
