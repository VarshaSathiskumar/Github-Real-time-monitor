import react from '@vitejs/plugin-react'
import path from 'node:path'
import { defineConfig } from 'vitest/config'

// The dev server proxies API and WebSocket traffic to the analytics service.
const backend = process.env.VITE_DEV_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/v3': backend,
      '/swagger-ui': backend,
      '/ws': { target: backend, ws: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'node',
  },
})
