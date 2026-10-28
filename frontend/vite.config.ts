import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// `npm run dev` talks to the desks running in docker compose, through the same paths nginx uses in production.
export default defineConfig({
  plugins: [react()],
  // AG Grid alone is over 1 MB minified; one chunk is fine for a demo app.
  build: { chunkSizeWarningLimit: 2000 },
  server: {
    port: 5173,
    proxy: {
      '/cafe/api': { target: 'http://localhost:8081', rewrite: (path) => path.replace(/^\/cafe/, '') },
      '/roaster/api': { target: 'http://localhost:8082', rewrite: (path) => path.replace(/^\/roaster/, '') },
    },
  },
})
