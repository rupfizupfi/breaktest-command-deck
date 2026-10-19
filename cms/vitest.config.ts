/**
 * Pure-logic unit tests (node environment, no DOM). src/test/frontend/ sits outside
 * tsconfig.json's include and is typechecked by its own src/test/frontend/tsconfig.json.
 *
 * Must not import vite.config.ts / vite.generated.ts: Vaadin's Flow plugins need generated/
 * scaffolding and the dev-mode folder layout.
 */
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/test/frontend/**/*.test.ts'],
  },
});
