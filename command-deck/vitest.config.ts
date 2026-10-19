/**
 * Pure-logic unit tests only (node environment, no DOM). Tests live in src/test/frontend/, outside
 * tsconfig.json's include, and are typechecked by src/test/frontend/tsconfig.json.
 *
 * Must not import vite.config.ts / vite.generated.ts: Vaadin's config needs generated/ scaffolding
 * and merges cms's file routes. Component tests against @vaadin web components would need Vitest
 * browser mode (jsdom has no shadow DOM).
 */
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/test/frontend/**/*.test.ts'],
  },
});
