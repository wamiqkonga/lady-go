import { describe, expect, it } from 'vitest'
import { STEPS, WORK_STEPS, REQUIRED_STEPS, countDone, firstIncompleteIndex, latestDocument, stepState, type RegistrationData } from './steps'

const vehicle = { id: 'v', registrationNumber: 'JK01', vehicleType: 'BIKE' } as RegistrationData['vehicle']
const accepted = { accepted: true } as RegistrationData['declaration']

function allDocs(overrides: Record<string, object> = {}) {
  return ['LICENSE', 'VEHICLE_REGISTRATION', 'FITNESS_CERTIFICATE', 'SELFIE']
    .map((type) => ({ id: type, type, status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', ...overrides[type] })) as RegistrationData['documents']
}

describe('steps', () => {
  it('starts at the vehicle step for a brand-new captain', () => {
    expect(firstIncompleteIndex({ vehicle: null, documents: [], declaration: null })).toBe(0)
    expect(countDone({ vehicle: null, documents: [], declaration: null })).toBe(0)
  })

  it('resumes on the first step still missing, skipping finished ones', () => {
    const docs = allDocs().filter((d) => d.type !== 'SELFIE')
    const index = firstIncompleteIndex({ vehicle, documents: docs, declaration: null })
    expect(STEPS[index].id).toBe('SELFIE')
  })

  it('treats a rejected RC photo as needing a fix on the vehicle step, and resumes there', () => {
    const data = { vehicle, documents: allDocs({ VEHICLE_REGISTRATION: { status: 'REJECTED' } }), declaration: accepted }
    const vehicleStep = STEPS.find((s) => s.id === 'vehicle')!
    expect(stepState(vehicleStep, data)).toBe('fix')
    expect(STEPS[firstIncompleteIndex(data)].id).toBe('vehicle')
  })

  it('needs both the vehicle number and the RC photo before the vehicle step is done', () => {
    const vehicleStep = STEPS.find((s) => s.id === 'vehicle')!
    expect(stepState(vehicleStep, { vehicle, documents: [], declaration: null })).toBe('todo')
    expect(stepState(vehicleStep, { vehicle: null, documents: allDocs(), declaration: null })).toBe('todo')
  })

  it('never blocks on the optional licence or fitness certificate', () => {
    const docs = allDocs().filter((d) => d.type === 'VEHICLE_REGISTRATION' || d.type === 'SELFIE')
    const data = { vehicle, documents: docs, declaration: accepted }
    expect(countDone(data)).toBe(REQUIRED_STEPS.length)
    expect(STEPS[firstIncompleteIndex(data)].kind).toBe('review')
  })

  it('has exactly the agreed steps: vehicle, optional licence, selfie, declaration', () => {
    expect(WORK_STEPS.map((s) => s.id)).toEqual(['vehicle', 'LICENSE', 'SELFIE', 'declaration'])
    expect(STEPS.find((s) => s.id === 'LICENSE')).toMatchObject({ optional: true })
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
    expect(countDone(data)).toBe(REQUIRED_STEPS.length)
    expect(STEPS[firstIncompleteIndex(data)].kind).toBe('review')
  })
})
