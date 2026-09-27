import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// PrivacyMask frontend (Phase 13). Dev server defaults to http://localhost:5173;
// point the backend at this origin via PRIVACYMASK_CORS_ALLOWED_ORIGINS.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  preview: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    globals: false,
  },
});
