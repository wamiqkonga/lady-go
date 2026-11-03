import type { components } from './api-types'

export type SafetySummary = components['schemas']['SafetyIncidentSummaryResponse']
export type SafetyDetail = components['schemas']['SafetyIncidentDetailResponse']
export type SafetyOutcome = NonNullable<components['schemas']['SafetyIncidentResolveRequest']['outcome']>

export const OUTCOMES: { value: SafetyOutcome; label: string }[] = [
  { value: 'RESOLVED_BY_PHONE', label: 'Sorted out by phone' },
  { value: 'POLICE_INFORMED', label: 'Police informed' },
  { value: 'ACTION_TAKEN_AGAINST_DRIVER', label: 'Action taken against the driver' },
  { value: 'ACTION_TAKEN_AGAINST_RIDER', label: 'Action taken against the rider' },
  { value: 'FALSE_ALARM', label: 'False alarm' },
  { value: 'NO_ACTION_NEEDED', label: 'No action needed' },
  { value: 'OTHER', label: 'Other (explain in the note)' },
]

export const TYPE_LABELS: Record<string, string> = {
  SOS: 'SOS',
  REPORT_DRIVER: 'Driver reported',
  REPORT_CUSTOMER: 'Rider reported',
  OTHER: 'Other',
}

export const STATUS_LABELS: Record<string, string> = {
  OPEN: 'Open',
  ACKNOWLEDGED: 'Acknowledged',
  ESCALATED: 'Escalated',
  RESOLVED: 'Resolved',
}

/** "3 min ago", "2 h ago": short and honest; an unresolved SOS older than a few minutes should look old. */
export function ageLabel(iso: string | undefined, now: Date = new Date()): string {
  if (!iso) return '—'
  const seconds = Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${hours} h ago`
  return `${Math.floor(hours / 24)} days ago`
}
