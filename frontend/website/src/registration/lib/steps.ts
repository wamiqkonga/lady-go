import type { components } from './api-types'

type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']
type VehicleResponse = components['schemas']['VehicleResponse']
type DeclarationStatusResponse = components['schemas']['DeclarationStatusResponse']
export type DocumentType = NonNullable<DriverDocumentResponse['type']>

export type Step =
  | { id: 'vehicle'; kind: 'vehicle'; title: string }
  | { id: DocumentType; kind: 'document'; title: string; tip: string; needsNumber?: boolean; needsExpiry?: boolean; optional?: boolean }
  | { id: 'SELFIE'; kind: 'selfie'; title: string }
  | { id: 'declaration'; kind: 'declaration'; title: string }
  | { id: 'review'; kind: 'review'; title: string }

/** Captains register on a two-wheeler with: (1) vehicle number + RC photo (+ optional fitness certificate),
 *  (2) an optional driving licence, (3) a selfie, (4) the self-declaration. */
export const STEPS: Step[] = [
  { id: 'vehicle', kind: 'vehicle', title: 'Your two-wheeler' },
  { id: 'LICENSE', kind: 'document', title: 'Driving licence (optional)', needsNumber: true, needsExpiry: true, optional: true,
    tip: 'Photograph the front of your licence on a flat surface in good light. All four corners and the text must be sharp. You can skip this and add it later.' },
  { id: 'SELFIE', kind: 'selfie', title: 'Selfie' },
  { id: 'declaration', kind: 'declaration', title: 'Self-declaration' },
  { id: 'review', kind: 'review', title: 'Review and finish' },
]

/** The RC photo and the fitness certificate are uploaded on the vehicle step. */
export const RC_STEP = {
  id: 'VEHICLE_REGISTRATION' as DocumentType,
  title: 'Vehicle registration (RC) photo',
  tip: 'Photograph the RC card or booklet page showing the registration number and owner name.',
}
export const FITNESS_STEP = {
  id: 'FITNESS_CERTIFICATE' as DocumentType,
  title: 'Fitness certificate (optional)',
  tip: 'Only if you have one. Photograph the whole certificate, with the validity date visible.',
}

export type StepState = 'done' | 'fix' | 'todo'

export type RegistrationData = {
  vehicle: VehicleResponse | null
  documents: DriverDocumentResponse[]
  declaration: DeclarationStatusResponse | null
}

export function latestDocument(documents: DriverDocumentResponse[], type: DocumentType): DriverDocumentResponse | undefined {
  return documents
    .filter((d) => d.type === type)
    .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))[0]
}

export function stepState(step: Step, data: RegistrationData): StepState {
  switch (step.kind) {
    case 'vehicle': {
      const rc = latestDocument(data.documents, RC_STEP.id)
      if (rc?.status === 'REJECTED') return 'fix'
      return data.vehicle && rc ? 'done' : 'todo'
    }
    case 'document':
    case 'selfie': {
      const doc = latestDocument(data.documents, step.id)
      if (!doc) return 'todo'
      return doc.status === 'REJECTED' ? 'fix' : 'done'
    }
    case 'declaration':
      return data.declaration?.accepted ? 'done' : 'todo'
    case 'review':
      return 'todo'
  }
}

export function isOptional(step: Step): boolean {
  return step.kind === 'document' && !!step.optional
}

/** Steps a captain sees (everything except the closing review screen). */
export const WORK_STEPS = STEPS.filter((s) => s.kind !== 'review')

/** Steps that must be finished before the application is reviewed (optional ones never block it). */
export const REQUIRED_STEPS = WORK_STEPS.filter((s) => !isOptional(s))

export function countDone(data: RegistrationData): number {
  return REQUIRED_STEPS.filter((s) => stepState(s, data) === 'done').length
}

/** Index in STEPS of the first required step still needing the captain, else the review screen. */
export function firstIncompleteIndex(data: RegistrationData): number {
  const index = STEPS.findIndex((s) => s.kind !== 'review' && !isOptional(s) && stepState(s, data) !== 'done')
  return index === -1 ? STEPS.length - 1 : index
}
