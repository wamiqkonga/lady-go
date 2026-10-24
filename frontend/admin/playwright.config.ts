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
  // The idle-timeout test waits out a genuine 45s client-side timer (VITE_E2E_IDLE_TIMEOUT_MS) on
  // top of its own setup - the default 30s would cut that off mid-wait.
  timeout: 60_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // 0, unconditionally: every real login in this suite now uses its own dedicated, independently-
  // enrolled account (see e2e/env.ts and scripts/ci/e2e-seed.sh), which removes the TOTP-replay
  // collision a shared account could hit - there is no longer a known flake class for a retry to
  // paper over, so a retry succeeding would misleadingly hide a real regression instead.
  retries: 0,
  workers: 1,
  reporter: process.env.CI ? [['html', { outputFolder: 'playwright-report' }], ['github']] : 'list',
  use: {
    baseURL: process.env.ADMIN_WEB_E2E_BASE_URL ?? 'http://localhost:5174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
