import { defineConfig, devices } from '@playwright/test'

/**
 * E2E suite for the admin web (docs/development/admin-web-manual-test.md is the human click-through
 * version of the same checklist; this is the automated one, run in CI against a real backend +
 * Postgres + seeded data, never mocked - see .github/workflows/admin-web-e2e.yml and
 * scripts/ci/e2e-seed.sh).
 *
 * Needs ADMIN_WEB_E2E_BASE_URL (the already-running admin web dev server) and credentials env vars
 * from the seed step (see e2e/env.ts) - every spec file skips itself with a clear message if they're
 * not set, so a plain local `npx playwright test` without that setup doesn't fail confusingly.
 */
export default defineConfig({
  testDir: './e2e',
  // Several tests do a real login, and env.ts's freshTotpCodeForLogin enforces a 35s minimum gap
  // between two real logins for the same account (TOTP replay avoidance) - the default 30s test
  // timeout could get consumed entirely by that wait alone, aborting before the test's own
  // assertions ever run.
  timeout: 100_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['html', { outputFolder: 'playwright-report' }], ['github']] : 'list',
  use: {
    baseURL: process.env.ADMIN_WEB_E2E_BASE_URL ?? 'http://localhost:5174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
