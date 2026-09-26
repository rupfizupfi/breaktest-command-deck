/**
 * Pure-logic unit tests only (node environment, no browser, no DOM). Tests live in
 * src/test/frontend/ — outside tsconfig.json's include — so the typecheck gate
 * (script/typecheck.ps1 → `tsc --noEmit -p tsconfig.json`) never sees vitest's types;
 * src/test/frontend/tsconfig.json typechecks them separately.
 *
 * Deliberately standalone: it must NOT import vite.config.ts / vite.generated.ts —
 * Vaadin's config wires Flow plugins that need generated/ scaffolding and a dev-mode
 * folder layout. Component tests against @vaadin web components need Vitest browser
 * mode (jsdom cannot do shadow DOM) and are a later rung, not yet built.
 */
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/test/frontend/**/*.test.ts'],
  },
});
