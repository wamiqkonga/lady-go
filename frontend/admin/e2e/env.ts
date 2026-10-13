import { generateSync } from 'otplib'

/** Everything the seed step (scripts/ci/e2e-seed.sh) hands this suite, via env vars set by the CI
 *  workflow - never committed, never a repo file, generated fresh and thrown away every CI run
 *  (see that script's own header comment for why that's a different situation from a developer's
 *  persistent machine, where scripts/dev-up.sh instead prints secrets to the terminal only). */
export interface E2eEnv {
  apiBaseUrl: string
  superAdmin: { email: string; password: string; totpSecret: string }
  admin: { email: string; password: string; totpSecret: string }
  /** Mutated to completion (approved) by admin-review-flow.spec.ts - never read by security-checks. */
  driverId: string
  /** Left untouched by admin-review-flow.spec.ts, so security-checks.spec.ts's document-view/expiry/
   *  rejection-note tests have pending documents to work with regardless of file execution order. */
  secondaryDriverId: string
  unknownDriverId: string
}

const REQUIRED = [
  'ADMIN_WEB_E2E_API_BASE_URL',
  'ADMIN_WEB_E2E_SUPER_ADMIN_EMAIL',
  'ADMIN_WEB_E2E_SUPER_ADMIN_PASSWORD',
  'ADMIN_WEB_E2E_SUPER_ADMIN_TOTP_SECRET',
  'ADMIN_WEB_E2E_ADMIN_EMAIL',
  'ADMIN_WEB_E2E_ADMIN_PASSWORD',
  'ADMIN_WEB_E2E_ADMIN_TOTP_SECRET',
  'ADMIN_WEB_E2E_DRIVER_ID',
  'ADMIN_WEB_E2E_SECONDARY_DRIVER_ID',
] as const

export function readE2eEnv(): E2eEnv | null {
  if (REQUIRED.some((key) => !process.env[key])) {
    return null
  }
  return {
    apiBaseUrl: process.env.ADMIN_WEB_E2E_API_BASE_URL!,
    superAdmin: {
      email: process.env.ADMIN_WEB_E2E_SUPER_ADMIN_EMAIL!,
      password: process.env.ADMIN_WEB_E2E_SUPER_ADMIN_PASSWORD!,
      totpSecret: process.env.ADMIN_WEB_E2E_SUPER_ADMIN_TOTP_SECRET!,
    },
    admin: {
      email: process.env.ADMIN_WEB_E2E_ADMIN_EMAIL!,
      password: process.env.ADMIN_WEB_E2E_ADMIN_PASSWORD!,
      totpSecret: process.env.ADMIN_WEB_E2E_ADMIN_TOTP_SECRET!,
    },
    driverId: process.env.ADMIN_WEB_E2E_DRIVER_ID!,
    secondaryDriverId: process.env.ADMIN_WEB_E2E_SECONDARY_DRIVER_ID!,
    // A syntactically valid but never-seeded UUID - "other-driver ID tampering" (manually editing the
    // URL to an id you don't otherwise have) needs a value guaranteed absent from the database.
    unknownDriverId: '00000000-0000-0000-0000-000000000000',
  }
}

/** otplib v13's defaults (SHA-1, 6 digits, 30s period) match TotpService on the backend. */
export function currentTotpCode(secret: string): string {
  return generateSync({ secret })
}

// Deliberately a flat 35s minimum gap between two real logins for the SAME account, not step-boundary
// math - computing "is this the same 30s step as last time" and only waiting when it collides sounds
// tighter, but it let a wait get truncated by the calling test's own default timeout (the wait itself
// could eat most of a 30s test budget, aborting before the assertions after login even ran). A flat,
// unconditional gap enforced BEFORE generating the code removes that race entirely, at the cost of
// (rarely) waiting slightly longer than strictly necessary.
const MINIMUM_GAP_MS = 35_000
const lastLoginAttemptAtBySecret = new Map<string, number>()

/**
 * A code safe to use for a real login for `secret` - waits out at least 35s since this process last
 * generated one for the same secret. Every *real, must-succeed* admin login in this suite uses this
 * (not `currentTotpCode` directly) - the backend's TOTP replay rejection
 * (AdminAuthService.updateMfaLastUsedStepIfNewer) correctly treats two independently-computed-but-
 * identical codes for the same account in the same 30s window as a replay, and this suite does
 * several real logins for the same super-admin account in one run. Not needed for the deliberately-
 * wrong-code test (never accepted anyway) or for a replay test's own second, intentionally-repeated
 * use of an already-generated code.
 *
 * Known flake, observed only on a loaded local dev machine running Postgres+backend+admin-web+
 * Chromium all at once: occasionally a single real login - most often the very first one in a run,
 * where no gap wait happens at all - gets UNAUTHORIZED on a correctly-generated, correctly-timed
 * code. Independently cross-checked the TOTP math (otplib vs. a from-scratch reimplementation of
 * TotpService's own algorithm, same secret, same instant - identical codes) and host/JVM clocks
 * (agree) - this is not an algorithm or clock-skew bug. Most likely a scheduling delay somewhere
 * between code generation and the request actually reaching the backend (GC pause, CPU contention
 * from the other services sharing this machine) pushing it just outside TotpService's own +/-1-step
 * tolerance window. CI runs on a dedicated, unloaded runner and playwright.config.ts sets retries: 1
 * specifically for this class of transient failure - if it reproduces consistently in CI (not just
 * occasionally), that would mean this explanation is wrong and it needs real investigation there.
 */
export async function freshTotpCodeForLogin(secret: string): Promise<string> {
  const lastAttemptAt = lastLoginAttemptAtBySecret.get(secret)
  if (lastAttemptAt !== undefined) {
    const elapsed = Date.now() - lastAttemptAt
    if (elapsed < MINIMUM_GAP_MS) {
      await new Promise((resolve) => setTimeout(resolve, MINIMUM_GAP_MS - elapsed))
    }
  }
  lastLoginAttemptAtBySecret.set(secret, Date.now())
  return generateSync({ secret })
}
