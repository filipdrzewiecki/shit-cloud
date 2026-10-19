import { defineConfig } from 'vitest/config'

// Unit tests for browser code with no server behind it: the app bridge and the
// SDK that ShitCloud injects into apps (a backend resource, loaded as text).
// Both are tested against hand-made windows, so no DOM is needed.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
