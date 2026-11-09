/**
 * The captain's full name, checked the same way the server checks it (DriverFullName.java): 2 to 80 characters
 * after trimming, letters from any script with their combining marks, single spaces, hyphens, apostrophes and
 * dots; no digits, markup or symbols. The server stays the authority; this only gives instant feedback.
 */
export const NAME_MAX = 80
export const NAME_ERROR =
  'Enter your full name using letters, spaces, hyphens, apostrophes and dots only (2 to 80 characters).'

const ALLOWED = /^(?:[\p{L}\p{M} .'\u2019-]|\u200C|\u200D)+$/u

/** Trims and collapses repeated spaces, the same cleaning the server applies. */
export function cleanFullName(typed: string): string {
  return typed.normalize('NFC').trim().replace(/ {2,}/g, ' ')
}

/** An error sentence for the captain, or null when the name is acceptable. */
export function fullNameError(typed: string): string | null {
  const name = cleanFullName(typed)
  const chars = Array.from(name)
  if (chars.length < 2 || chars.length > NAME_MAX || !ALLOWED.test(name)) return NAME_ERROR
  const letters = chars.filter((c) => /\p{L}/u.test(c)).length
  return letters < 2 ? NAME_ERROR : null
}

/** "Congratulations, <first name>": the first word of the name, or null when there is no usable name. */
export function firstName(fullName: string | null | undefined): string | null {
  const first = cleanFullName(fullName ?? '').split(' ')[0]
  return first ? first : null
}
