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
  /** Mutated to completion (approved) by admin-review-flow.spec.ts - never read by security-checks. */
  driverId: string
  /** Left untouched by admin-review-flow.spec.ts, so security-checks.spec.ts's document-view/expiry/
   *  rejection-note tests have pending documents to work with regardless of file execution order. */
  secondaryDriverId: string
  unknownDriverId: string
}

const REQUIRED_BASE = [
  'ADMIN_WEB_E2E_API_BASE_URL',
  'ADMIN_WEB_E2E_ADMIN_EMAIL',
  'ADMIN_WEB_E2E_ADMIN_PASSWORD',
  'ADMIN_WEB_E2E_ADMIN_TOTP_SECRET',
  'ADMIN_WEB_E2E_DRIVER_ID',
  'ADMIN_WEB_E2E_SECONDARY_DRIVER_ID',
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
    driverId: process.env.ADMIN_WEB_E2E_DRIVER_ID!,
    secondaryDriverId: process.env.ADMIN_WEB_E2E_SECONDARY_DRIVER_ID!,
    // A syntactically valid but never-seeded UUID - "other-driver ID tampering" (manually editing the
    // URL to an id you don't otherwise have) needs a value guaranteed absent from the database.
    unknownDriverId: '00000000-0000-0000-0000-000000000000',
  }
}

/** otplib v13's defaults (SHA-1, 6 digits, 30s period) match TotpService on the backend. Safe to call
 *  directly with no waiting or gap-tracking - every real login in this suite uses its own dedicated
 *  account (see SUPER_ADMIN_KEYS above), so no two real logins ever share a secret and there is no
 *  step for them to collide on. */
export function currentTotpCode(secret: string): string {
  return generateSync({ secret })
}
