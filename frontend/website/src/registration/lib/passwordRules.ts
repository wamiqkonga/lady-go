/** Same minimum as the server (DriverPasswordPolicy). The server is the authority; this only saves a round trip. */
export const MIN_PASSWORD_LENGTH = 10

export const PASSWORD_HINT =
  'At least 10 characters. A few words together are easy to remember and hard to guess. Do not use your phone number or a very common password.'

/** A plain-language problem with the two fields, or null when they are fine to send. */
export function passwordProblem(password: string, confirm: string): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) return `Use at least ${MIN_PASSWORD_LENGTH} characters.`
  if (password !== confirm) return 'The two passwords do not match.'
  return null
}
