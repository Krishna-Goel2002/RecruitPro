import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/** Unit and page tests (jsdom). Kept apart from vite.config.ts so the brand plugin and build settings stay untouched. */
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
