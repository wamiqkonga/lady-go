import { defineConfig, devices } from '@playwright/test'

/**
 * E2E suite for the website (driver registration flow), run in CI against a real backend + Postgres +
 * seeded data, never mocked (see .github/workflows/driver-registration-e2e.yml and
 * scripts/ci/driver-registration-e2e-seed.sh).
 *
 * Needs DRIVER_REG_E2E_BASE_URL (the already-running dev server) and credentials/env vars from the
 * seed step (see e2e/env.ts) - every spec file skips itself with a clear message if they're not set.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // 0, unconditionally: every test uses its own dedicated seeded account - same reasoning as
  // frontend/admin's suite (no shared OTP state for a retry to paper over a real collision with).
  retries: 0,
  workers: 1,
  reporter: process.env.CI ? [['html', { outputFolder: 'playwright-report' }], ['github']] : 'list',
  use: {
    baseURL: process.env.DRIVER_REG_E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], permissions: ['camera'] } }],
})
