import { generateSync } from 'otplib'

export interface Credentials {
  email: string
  password: string
  totpSecret: string
}

/** One key per test that performs a real login - see scripts/ci/e2e-seed.sh's header comment for why
 *  every real login gets its own dedicated, independently-enrolled SUPER_ADMIN account rather than
 *  sharing one across the suite (root cause: MFA enrolment itself consumes a TOTP step the same way
 *  login does, so a shared account risked the seed step's own enrolment-confirm colliding with the
 *  first test to use that account). REPLAY is used twice, by the replay test itself, on purpose. */
const SUPER_ADMIN_KEYS = [
  'LOGIN',
  'QUEUE',
  'REVIEW',
  'IDLE',
  'SIGNOUT',
  'WRONGCODE',
  'REPLAY',
  'DOCEXPIRY',
  'TAMPER',
  'COOKIES',
  'NOSTORAGE',
  'MARKUP',
  'ROLECHECK',
  'RIDES',
  'FLAGS',
  'AUDITLOG',
  'CONFIGEDIT',
] as const

type SuperAdminKey = (typeof SUPER_ADMIN_KEYS)[number]

/** Everything the seed step (scripts/ci/e2e-seed.sh) hands this suite, via env vars set by the CI
 *  workflow - never committed, never a repo file, generated fresh and thrown away every CI run
 *  (see that script's own header comment for why that's a different situation from a developer's
 *  persistent machine, where scripts/dev-up.sh instead prints secrets to the terminal only). */
export interface E2eEnv {
  apiBaseUrl: string
  /** superAdmins.LOGIN, superAdmins.QUEUE, etc. - one dedicated account per test, never shared. */
  superAdmins: Record<SuperAdminKey, Credentials>
  admin: Credentials
  /** A second, independent plain-ADMIN account - security-checks.spec.ts's raw-fetch role check
   *  already uses `admin` above, so the UI-based read-only check in admin-oversight-flow.spec.ts gets
   *  its own account rather than sharing a login across two spec files. */
  admin2: Credentials
  /** Mutated to completion (approved) by admin-review-flow.spec.ts - never read by security-checks. */
  driverId: string
  /** Left untouched by admin-review-flow.spec.ts, so security-checks.spec.ts's document-view/expiry/
   *  rejection-note tests have pending documents to work with regardless of file execution order. */
  secondaryDriverId: string
  unknownDriverId: string
  /** A COMPLETED cash booking with one open (unreviewed) RIDER_SAFETY_CANCEL flag, seeded directly via
   *  SQL (scripts/ci/e2e-seed.sh) rather than driven through the real matching/ride-lifecycle APIs -
   *  proportionate for fixture data the oversight screens just need to read/review, not for exercising
   *  the lifecycle itself (that's rides/service's own test suite's job). */
  flaggedBookingId: string
  flagId: string
}

const REQUIRED_BASE = [
  'ADMIN_WEB_E2E_API_BASE_URL',
  'ADMIN_WEB_E2E_ADMIN_EMAIL',
  'ADMIN_WEB_E2E_ADMIN_PASSWORD',
  'ADMIN_WEB_E2E_ADMIN_TOTP_SECRET',
  'ADMIN_WEB_E2E_ADMIN2_EMAIL',
  'ADMIN_WEB_E2E_ADMIN2_PASSWORD',
  'ADMIN_WEB_E2E_ADMIN2_TOTP_SECRET',
  'ADMIN_WEB_E2E_DRIVER_ID',
  'ADMIN_WEB_E2E_SECONDARY_DRIVER_ID',
  'ADMIN_WEB_E2E_FLAGGED_BOOKING_ID',
  'ADMIN_WEB_E2E_FLAG_ID',
] as const

function requiredVarsForKey(key: SuperAdminKey): string[] {
  return [
    `ADMIN_WEB_E2E_SUPER_${key}_EMAIL`,
    `ADMIN_WEB_E2E_SUPER_${key}_PASSWORD`,
    `ADMIN_WEB_E2E_SUPER_${key}_TOTP_SECRET`,
  ]
}

export function readE2eEnv(): E2eEnv | null {
  const allRequired = [...REQUIRED_BASE, ...SUPER_ADMIN_KEYS.flatMap(requiredVarsForKey)]
  if (allRequired.some((key) => !process.env[key])) {
    return null
  }

  const superAdmins = Object.fromEntries(
    SUPER_ADMIN_KEYS.map((key) => [
      key,
      {
        email: process.env[`ADMIN_WEB_E2E_SUPER_${key}_EMAIL`]!,
        password: process.env[`ADMIN_WEB_E2E_SUPER_${key}_PASSWORD`]!,
        totpSecret: process.env[`ADMIN_WEB_E2E_SUPER_${key}_TOTP_SECRET`]!,
      },
    ]),
  ) as Record<SuperAdminKey, Credentials>

  return {
    apiBaseUrl: process.env.ADMIN_WEB_E2E_API_BASE_URL!,
    superAdmins,
    admin: {
      email: process.env.ADMIN_WEB_E2E_ADMIN_EMAIL!,
      password: process.env.ADMIN_WEB_E2E_ADMIN_PASSWORD!,
      totpSecret: process.env.ADMIN_WEB_E2E_ADMIN_TOTP_SECRET!,
    },
    admin2: {
      email: process.env.ADMIN_WEB_E2E_ADMIN2_EMAIL!,
      password: process.env.ADMIN_WEB_E2E_ADMIN2_PASSWORD!,
      totpSecret: process.env.ADMIN_WEB_E2E_ADMIN2_TOTP_SECRET!,
    },
    driverId: process.env.ADMIN_WEB_E2E_DRIVER_ID!,
    secondaryDriverId: process.env.ADMIN_WEB_E2E_SECONDARY_DRIVER_ID!,
    // A syntactically valid but never-seeded UUID - "other-driver ID tampering" (manually editing the
    // URL to an id you don't otherwise have) needs a value guaranteed absent from the database.
    unknownDriverId: '00000000-0000-0000-0000-000000000000',
    flaggedBookingId: process.env.ADMIN_WEB_E2E_FLAGGED_BOOKING_ID!,
    flagId: process.env.ADMIN_WEB_E2E_FLAG_ID!,
  }
}

/**
 * Same as readE2eEnv(), except in CI (process.env.CI is set by every CI runner, including GitHub
 * Actions) a missing/empty required var throws instead of returning null. Every spec file uses this,
 * not readE2eEnv() directly - test.skip(!env, ...) on a null env makes the whole suite report as
 * "13 skipped" with an overall PASSING exit code, which is exactly how a real seed-step bug (see
 * scripts/ci/e2e-seed.sh's header comment for the one that actually happened) went unnoticed as a
 * false green for two separate CI runs before being caught by manually reading the job's test-count
 * summary rather than trusting its pass/fail status. Locally, with no CI env var set, a missing
 * credential still degrades to a graceful skip (so `npx playwright test` without the full seed setup
 * doesn't fail confusingly) - only CI is held to "every required credential must be present, or the
 * run is a hard failure, never a quiet skip."
 */
export function readE2eEnvOrThrowInCi(): E2eEnv | null {
  const env = readE2eEnv()
  if (env) return env
  if (!process.env.CI) return null

  const allRequired = [...REQUIRED_BASE, ...SUPER_ADMIN_KEYS.flatMap(requiredVarsForKey)]
  const missing = allRequired.filter((key) => !process.env[key])
  throw new Error(
    `ADMIN_WEB_E2E_* env vars missing in CI (seed step must have failed without the job itself ` +
      `failing - see scripts/ci/e2e-seed.sh): ${missing.join(', ')}`,
  )
}

/** otplib v13's defaults (SHA-1, 6 digits, 30s period) match TotpService on the backend. Safe to call
 *  directly with no waiting or gap-tracking - every real login in this suite uses its own dedicated
 *  account (see SUPER_ADMIN_KEYS above), so no two real logins ever share a secret and there is no
 *  step for them to collide on. */
export function currentTotpCode(secret: string): string {
  return generateSync({ secret })
}
