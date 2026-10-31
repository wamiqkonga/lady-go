export type PhoneResult = { phone: string } | { error: string }

const INVALID =
  'Enter your 10-digit mobile number, for example 9900000000. For a number outside India, include the country code, for example +44 7911 123456.'

/**
 * Turns what a captain typed into the international format the API requires ("+" and a country code, 8 to
 * 15 digits in all). A plain 10-digit Indian mobile number is the common case, so it gets "+91" added;
 * spaces, dashes and brackets are ignored. Anything else must already carry a country code.
 */
export function normalizePhone(typed: string): PhoneResult {
  const compact = typed.replace(/[\s\-().]/g, '')
  const digitsOnly = compact.replace(/^\+/, '')
  if (!/^\d+$/.test(digitsOnly)) return { error: INVALID }

  if (compact.startsWith('+')) return isInternational(digitsOnly) ? { phone: `+${digitsOnly}` } : { error: INVALID }
  if (digitsOnly.startsWith('00')) {
    const rest = digitsOnly.slice(2)
    return isInternational(rest) ? { phone: `+${rest}` } : { error: INVALID }
  }
  if (/^[6-9]\d{9}$/.test(digitsOnly)) return { phone: `+91${digitsOnly}` }
  if (/^0[6-9]\d{9}$/.test(digitsOnly)) return { phone: `+91${digitsOnly.slice(1)}` }
  if (/^91[6-9]\d{9}$/.test(digitsOnly)) return { phone: `+${digitsOnly}` }
  return { error: INVALID }
}

function isInternational(digits: string): boolean {
  return /^[1-9]\d{7,14}$/.test(digits)
}
