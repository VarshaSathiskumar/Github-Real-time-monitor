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
    rollupOptions: {
      output: {
        // Keep the two heavy visualisation libraries out of the app chunk so they cache independently.
        manualChunks: (id) => {
          if (id.includes('node_modules/cytoscape')) return 'cytoscape'
          if (id.includes('node_modules/recharts') || (id.includes('node_modules/d3-') && !id.includes('d3-force'))) return 'charts'
        },
      },
    },
  },
  test: {
    environment: 'node',
  },
})
