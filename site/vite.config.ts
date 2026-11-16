import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import brandPlugin from './src/design/brand/vite-plugin-brand'

// The landing site uses the same brand preset as the portals (src/design/brand/active.ts). The 'candidate' portal
// label is empty, so <title> is the plain brand name.
export default defineConfig({ plugins: [react(), brandPlugin({ portal: 'candidate' })] })
