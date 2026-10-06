import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the app is served from :5173 and the API from :8080.
// Proxying instead of calling the backend directly keeps every request same-origin,
// so CORS never enters the picture while developing.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
