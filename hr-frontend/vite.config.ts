import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import brandPlugin from './src/design/brand/vite-plugin-brand'

export default defineConfig({
  plugins: [react(), brandPlugin({ portal: 'staff' })],
  server: {
    host: 'localhost',
    port: 5174,
    strictPort: true,
  },
})
