import type { components } from './api-types'

type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']
type VehicleResponse = components['schemas']['VehicleResponse']
type DeclarationStatusResponse = components['schemas']['DeclarationStatusResponse']
export type DocumentType = NonNullable<DriverDocumentResponse['type']>

export type Step =
  | { id: 'vehicle'; kind: 'vehicle'; title: string }
  | { id: DocumentType; kind: 'document'; title: string; tip: string; needsNumber?: boolean; needsExpiry?: boolean }
  | { id: 'SELFIE'; kind: 'selfie'; title: string }
  | { id: 'declaration'; kind: 'declaration'; title: string }
  | { id: 'review'; kind: 'review'; title: string }

export const STEPS: Step[] = [
  { id: 'vehicle', kind: 'vehicle', title: 'Your vehicle' },
  { id: 'LICENSE', kind: 'document', title: 'Driving licence', needsNumber: true, needsExpiry: true,
    tip: 'Photograph the front of your licence on a flat surface in good light. All four corners and the text must be sharp.' },
  { id: 'VEHICLE_REGISTRATION', kind: 'document', title: 'Vehicle registration (RC) photo',
    tip: 'Photograph the RC card or booklet page showing the registration number and owner name.' },
  { id: 'VEHICLE_PHOTO', kind: 'document', title: 'Vehicle photo (plate visible)',
    tip: 'Stand a few steps back so the whole vehicle fits, with the number plate clearly readable.' },
  { id: 'INSURANCE', kind: 'document', title: 'Insurance', needsExpiry: true,
    tip: 'Use the page that shows your policy dates. Expired insurance cannot be approved.' },
  { id: 'FITNESS_CERTIFICATE', kind: 'document', title: 'Fitness certificate',
    tip: 'Photograph the whole certificate, with the validity date visible.' },
  { id: 'POLLUTION_CERTIFICATE', kind: 'document', title: 'Pollution certificate',
    tip: 'Photograph the whole certificate, with the validity date visible.' },
  { id: 'SELFIE', kind: 'selfie', title: 'Selfie' },
  { id: 'declaration', kind: 'declaration', title: 'Self-declaration' },
  { id: 'review', kind: 'review', title: 'Review and finish' },
]

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
    case 'vehicle':
      return data.vehicle ? 'done' : 'todo'
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

/** Steps a driver has to finish (everything except the closing review screen). */
export const WORK_STEPS = STEPS.filter((s) => s.kind !== 'review')

export function countDone(data: RegistrationData): number {
  return WORK_STEPS.filter((s) => stepState(s, data) === 'done').length
}

/** Index in STEPS of the first step still needing the driver, else the review screen. */
export function firstIncompleteIndex(data: RegistrationData): number {
  const index = STEPS.findIndex((s) => s.kind !== 'review' && stepState(s, data) !== 'done')
  return index === -1 ? STEPS.length - 1 : index
}
