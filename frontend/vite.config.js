import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server runs on 5173 (allowed by backend CORS in WebConfig).
// /api is proxied to the Spring Boot backend on :8080 so the SSE chat
// stream and timeline endpoints work without cross-origin during dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
