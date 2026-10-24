import { describe, expect, it } from 'vitest'
import { STEPS, WORK_STEPS, countDone, firstIncompleteIndex, latestDocument, stepState, type RegistrationData } from './steps'

const vehicle = { id: 'v', registrationNumber: 'JK01', vehicleType: 'CAB' } as RegistrationData['vehicle']
const accepted = { accepted: true } as RegistrationData['declaration']

function allDocs(overrides: Record<string, object> = {}) {
  return ['LICENSE', 'VEHICLE_REGISTRATION', 'VEHICLE_PHOTO', 'INSURANCE', 'FITNESS_CERTIFICATE', 'POLLUTION_CERTIFICATE', 'SELFIE']
    .map((type) => ({ id: type, type, status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', ...overrides[type] })) as RegistrationData['documents']
}

describe('steps', () => {
  it('starts at the vehicle step for a brand-new driver', () => {
    expect(firstIncompleteIndex({ vehicle: null, documents: [], declaration: null })).toBe(0)
    expect(countDone({ vehicle: null, documents: [], declaration: null })).toBe(0)
  })

  it('resumes on the first step still missing, skipping finished ones', () => {
    const docs = allDocs().filter((d) => d.type !== 'INSURANCE' && d.type !== 'SELFIE')
    const index = firstIncompleteIndex({ vehicle, documents: docs, declaration: null })
    expect(STEPS[index].id).toBe('INSURANCE')
  })

  it('treats a rejected document as needing a fix, and resumes there', () => {
    const data = { vehicle, documents: allDocs({ LICENSE: { status: 'REJECTED' } }), declaration: accepted }
    const licence = STEPS.find((s) => s.id === 'LICENSE')!
    expect(stepState(licence, data)).toBe('fix')
    expect(STEPS[firstIncompleteIndex(data)].id).toBe('LICENSE')
  })

  it('uses the newest document of a type, so a resubmission replaces a rejection', () => {
    const docs = [
      { id: 'old', type: 'LICENSE', status: 'REJECTED', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'new', type: 'LICENSE', status: 'PENDING', createdAt: '2026-01-02T00:00:00Z' },
    ] as RegistrationData['documents']
    expect(latestDocument(docs, 'LICENSE')?.id).toBe('new')
  })

  it('lands on the review screen once everything is done', () => {
    const data = { vehicle, documents: allDocs(), declaration: accepted }
    expect(countDone(data)).toBe(WORK_STEPS.length)
    expect(STEPS[firstIncompleteIndex(data)].kind).toBe('review')
  })
})
