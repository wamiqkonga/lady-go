/**
 * The only thing kept client-side across a reload: which step the wizard was on, and in-progress text
 * field values for forms not yet submitted (vehicle fields, document number/expiry inputs). Never a
 * document image, selfie blob, or any auth token - those either live only in memory until upload
 * succeeds, or never get written to storage at all (see auth.ts). sessionStorage, not localStorage -
 * gone when the tab closes, not shared across tabs/devices.
 */
const KEY = 'ladygo-driver-registration-resume-v1'

export type ResumeState = {
  step: string
  fields: Record<string, string>
}

export function loadResumeState(): ResumeState | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as ResumeState) : null
  } catch {
    return null
  }
}

export function saveResumeState(state: ResumeState): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(state))
  } catch {
    // Private mode / storage disabled / quota - resuming is a convenience, never required.
  }
}

export function clearResumeState(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // See saveResumeState.
  }
}
