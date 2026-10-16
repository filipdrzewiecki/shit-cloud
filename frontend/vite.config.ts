import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// No API proxy: the API is a separate service on its own origin in production,
// so local development is cross-origin too (VITE_API_BASE_URL).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
  },
})
