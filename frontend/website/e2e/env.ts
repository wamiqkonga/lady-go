import { readFileSync } from 'node:fs'

export interface E2eEnv {
  apiBaseUrl: string
  backendLog: string
  /** One phone number per test that performs a real OTP login - never shared, so no two tests can
   *  ever race on the same 6-digit code in the backend log (see env.ts and scripts/ci/
   *  driver-registration-e2e-seed.sh's own header comment for the analogous TOTP reasoning in
   *  frontend/admin's suite - same root cause class, different mechanism). */
  phones: Record<string, string>
}

const PHONE_KEYS = [
  'HAPPY_PATH', 'WRONG_FILE_TYPE', 'OVERSIZED_FILE', 'EXPIRED_INSURANCE', 'REJECT_RESUBMIT',
] as const

type PhoneKey = (typeof PHONE_KEYS)[number]

const REQUIRED_BASE = ['DRIVER_REG_E2E_API_BASE_URL', 'DRIVER_REG_E2E_BACKEND_LOG'] as const

export function readE2eEnv(): E2eEnv | null {
  const allRequired = [...REQUIRED_BASE, ...PHONE_KEYS.map((k) => `DRIVER_REG_E2E_PHONE_${k}`)]
  if (allRequired.some((key) => !process.env[key])) {
    return null
  }
  const phones = Object.fromEntries(
    PHONE_KEYS.map((key) => [key, process.env[`DRIVER_REG_E2E_PHONE_${key}`]!]),
  ) as Record<PhoneKey, string>
  return {
    apiBaseUrl: process.env.DRIVER_REG_E2E_API_BASE_URL!,
    backendLog: process.env.DRIVER_REG_E2E_BACKEND_LOG!,
    phones,
  }
}

/** Same "hard failure in CI, graceful skip locally" split as frontend/admin/e2e/env.ts - see that
 *  file's own doc comment for why a missing credential must never degrade to a silent "N skipped"
 *  with a passing exit code in CI specifically. */
export function readE2eEnvOrThrowInCi(): E2eEnv | null {
  const env = readE2eEnv()
  if (env) return env
  if (!process.env.CI) return null
  const allRequired = [...REQUIRED_BASE, ...PHONE_KEYS.map((k) => `DRIVER_REG_E2E_PHONE_${k}`)]
  const missing = allRequired.filter((key) => !process.env[key])
  throw new Error(`DRIVER_REG_E2E_* env vars missing in CI: ${missing.join(', ')}`)
}

/** Reads the most recently logged OTP code for a phone out of the real backend log -
 *  LoggingSmsSender (dev/test only) logs `operation=logging_sms_send phone={} templateKey=otp_code
 *  variables={code=XXXXXX, ...}`; there is no API that would ever return a code to a client, by
 *  design, so this is the only way an out-of-process test runner can get it. */
export function latestOtpCodeFor(phone: string, backendLogPath: string): string {
  const content = readFileSync(backendLogPath, 'utf8')
  const lines = content.split('\n').filter((line) => line.includes(`phone=${phone} `))
  const lastLine = lines.at(-1)
  if (!lastLine) {
    throw new Error(`No logged OTP found for ${phone} in ${backendLogPath}`)
  }
  const match = lastLine.match(/code=(\d{6})/)
  if (!match) {
    throw new Error(`Found a log line for ${phone} but no code= in it: ${lastLine}`)
  }
  return match[1]
}
