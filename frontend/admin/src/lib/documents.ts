/** How the review screens name a captain's documents, and which ones a two-wheeler captain actually needs. */

export const DOCUMENT_LABELS: Record<string, string> = {
  VEHICLE_REGISTRATION: 'Vehicle registration (RC) photo',
  SELFIE: 'Selfie',
  LICENSE: 'Driving licence',
  FITNESS_CERTIFICATE: 'Fitness certificate',
  INSURANCE: 'Insurance',
  VEHICLE_PHOTO: 'Vehicle photo',
  POLLUTION_CERTIFICATE: 'Pollution certificate',
  POLICE_VERIFICATION: 'Police verification',
  GOVERNMENT_ID: 'Government ID',
  PERMIT: 'Permit',
  PHOTO: 'Photo',
  OTHER: 'Other document',
}

export const DOCUMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: 'Waiting for review',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
}

export type Requirement = 'required' | 'optional' | 'not-required'

/**
 * Mirrors the registration steps (frontend/website/src/registration/lib/steps.ts) and AdminVerificationService:
 * the vehicle's RC photo and the selfie are required (the declaration is a separate step); the licence and the
 * fitness certificate are optional; the older insurance / vehicle photo / pollution types are no longer asked for.
 */
export function requirementOf(type: string | undefined): Requirement {
  switch (type) {
    case 'VEHICLE_REGISTRATION':
    case 'SELFIE':
      return 'required'
    case 'LICENSE':
    case 'FITNESS_CERTIFICATE':
    case 'POLICE_VERIFICATION': // only asked for once police verification is switched on
      return 'optional'
    default:
      return 'not-required'
  }
}

export const REQUIREMENT_LABELS: Record<Requirement, string> = {
  required: 'Required',
  optional: 'Optional',
  'not-required': 'Not required for two-wheelers',
}

export function documentLabel(type: string | undefined): string {
  if (!type) return 'Document'
  return DOCUMENT_LABELS[type] ?? type.charAt(0) + type.slice(1).toLowerCase().replace(/_/g, ' ')
}

export function documentStatusLabel(status: string | undefined): string {
  return status ? (DOCUMENT_STATUS_LABELS[status] ?? status) : '-'
}
