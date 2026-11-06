export const VERIFICATION_LABELS: Record<string, string> = {
  NOT_SUBMITTED: 'Registration not finished',
  PENDING_REVIEW: 'Waiting for review',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SUSPENDED: 'Suspended',
}

export const AVAILABILITY_LABELS: Record<string, string> = {
  ONLINE: 'Online',
  OFFLINE: 'Offline',
  BUSY: 'On a ride',
  ON_RIDE: 'On a ride',
  SUSPENDED: 'Suspended',
}

export function verificationLabel(status: string | undefined): string {
  return status ? (VERIFICATION_LABELS[status] ?? status) : '-'
}

export function availabilityLabel(status: string | undefined): string {
  return status ? (AVAILABILITY_LABELS[status] ?? status) : '-'
}

/** "No ratings yet" for a captain nobody has rated; otherwise "4.50 (12 ratings)". */
export function ratingText(avg: number | undefined, count: number | undefined): string {
  if (!count) return 'No ratings yet'
  return `${(avg ?? 0).toFixed(2)} (${count} ${count === 1 ? 'rating' : 'ratings'})`
}

export const NAME_MISSING = 'Name not provided'
