import { formatRupees } from './money'

/** Plain-words names for everything the backend writes to the audit log. Unknown actions fall back to sentence case. */
export const ACTION_LABELS: Record<string, string> = {
  ADMIN_LOGIN_UNLOCK: 'Unlocked a staff sign-in',
  ADMIN_MFA_RESET: 'Reset a staff authenticator',
  ADMIN_PASSWORD_RESET: 'Reset a staff password',
  ADMIN_USER_DEACTIVATED: 'Deactivated a staff account',
  ADMIN_USER_INVITED: 'Invited a staff member',
  ADMIN_USER_REACTIVATED: 'Re-activated a staff account',
  ADMIN_USER_ROLE_CHANGED: 'Changed a staff role',
  CONFIGURATION_CHANGED: 'Changed a setting',
  CUSTOMER_SUSPENDED: 'Suspended a customer',
  CUSTOMER_ACTIVATED: 'Re-activated a customer',
  DRIVER_ACTIVATED: 'Re-activated a driver',
  DRIVER_APPROVED: 'Approved a driver',
  DRIVER_REJECTED: 'Rejected a driver',
  DRIVER_SUSPENDED: 'Suspended a driver',
  DRIVER_DOCUMENT_APPROVED: 'Approved a document',
  DRIVER_DOCUMENT_REJECTED: 'Rejected a document',
  DRIVER_DOCUMENT_VIEWED: 'Viewed a driver document',
  DRIVER_PASSWORD_CHANGED: 'Driver changed their password',
  DRIVER_PASSWORD_RESET: 'Reset a driver password',
  DRIVER_PASSWORD_SET: 'Driver set a password',
  DRIVER_RANDOM_REVERIFICATION_TRIGGERED: 'Asked a driver to verify again',
  PRICING_RULE_CANCELLED: 'Cancelled a scheduled fare',
  PRICING_RULE_CREATED: 'Changed a fare',
  RIDE_FLAG_REVIEWED: 'Reviewed a ride flag',
  SAFETY_INCIDENT_ACKNOWLEDGED: 'Acknowledged a safety incident',
  SAFETY_INCIDENT_NOTE_ADDED: 'Added a note to a safety incident',
  SAFETY_INCIDENT_RESOLVED: 'Resolved a safety incident',
  VEHICLE_APPROVED: 'Approved a vehicle',
  VEHICLE_REJECTED: 'Rejected a vehicle',
}

const TARGET_LABELS: Record<string, string> = {
  drivers: 'Driver', driver_documents: 'Driver document', vehicles: 'Vehicle', users: 'Staff account', customers: 'Customer',
  safety_incidents: 'Safety incident', pricing_rules: 'Fare', ride_flags: 'Ride flag', configuration: 'Setting', bookings: 'Ride',
}

function sentence(code: string): string {
  const text = code.toLowerCase().replace(/_/g, ' ').trim()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

export function actionLabel(action: string | undefined): string {
  if (!action) return '-'
  return ACTION_LABELS[action] ?? sentence(action)
}

export function targetLabel(targetType: string | undefined): string {
  if (!targetType) return '-'
  return TARGET_LABELS[targetType] ?? sentence(targetType)
}

const MONEY_KEYS = new Set(['baseFare', 'perKm', 'perMinute', 'minimumFare', 'cancellationFee', 'serviceFeeFlat'])
const KEY_LABELS: Record<string, string> = {
  baseFare: 'Base fare', perKm: 'Per km', perMinute: 'Per minute', minimumFare: 'Minimum fare',
  cancellationFee: 'Cancellation fee', serviceFeeFlat: 'Service fee (flat)', serviceFeePct: 'Service fee (%)',
  oldValues: 'Before', newValues: 'After', cityId: 'City', vehicleType: 'Vehicle type', effectiveFrom: 'Starts',
  previousRuleId: 'Replaces fare', largeChangeConfirmed: 'Large change confirmed', reason: 'Reason', note: 'Note',
  outcome: 'Outcome', role: 'Role', invitedEmail: 'Invited email', policeVerificationRequired: 'Police verification required',
  incidentType: 'Incident type', bookingId: 'Ride', from: 'From', to: 'To',
}

function humanKey(key: string): string {
  if (KEY_LABELS[key]) return KEY_LABELS[key]
  return sentence(key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/ /g, '_'))
}

function humanValue(key: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return MONEY_KEYS.has(key) ? formatRupees(value) : String(value)
  if (typeof value === 'string') {
    if (MONEY_KEYS.has(key) && /^\d+$/.test(value)) return formatRupees(Number(value))
    if (/^[A-Z][A-Z_]+$/.test(value)) return sentence(value)
    if (/^\d{4}-\d{2}-\d{2}T/.test(value) && !Number.isNaN(Date.parse(value))) return new Date(value).toLocaleString()
    return value
  }
  if (Array.isArray(value)) return value.map((v) => humanValue(key, v)).join(', ')
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${humanKey(k)}: ${humanValue(k, v)}`)
      .join(' · ')
  }
  return String(value)
}

export type DetailPair = { label: string; value: string }

/** The metadata map as readable label/value pairs. Values stay plain text (React escapes them). */
export function detailPairs(metadata: Record<string, unknown> | undefined | null): DetailPair[] {
  if (!metadata) return []
  return Object.entries(metadata).map(([key, value]) => ({ label: humanKey(key), value: humanValue(key, value) }))
}
