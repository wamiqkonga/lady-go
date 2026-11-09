import { describe, expect, it } from 'vitest'
import { STEPS, WORK_STEPS, REQUIRED_STEPS, countDone, firstIncompleteIndex, latestDocument, stepState, type RegistrationData } from './steps'

const vehicle = { id: 'v', registrationNumber: 'JK01', vehicleType: 'BIKE' } as RegistrationData['vehicle']
const accepted = { accepted: true } as RegistrationData['declaration']

function allDocs(overrides: Record<string, object> = {}) {
  return ['LICENSE', 'VEHICLE_REGISTRATION', 'FITNESS_CERTIFICATE', 'SELFIE']
    .map((type) => ({ id: type, type, status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', ...overrides[type] })) as RegistrationData['documents']
}

describe('steps', () => {
  it('starts at the name step for a brand-new captain', () => {
    expect(STEPS[firstIncompleteIndex({ fullName: null, vehicle: null, documents: [], declaration: null })].id).toBe('name')
    expect(countDone({ fullName: null, vehicle: null, documents: [], declaration: null })).toBe(0)
  })

  it('starts at the vehicle step once the name is given', () => {
    expect(STEPS[firstIncompleteIndex({ fullName: 'Aisha Khan', vehicle: null, documents: [], declaration: null })].id).toBe('vehicle')
    expect(countDone({ fullName: 'Aisha Khan', vehicle: null, documents: [], declaration: null })).toBe(1)
  })

  it('sends a returning captain with everything but a name back to the name step first', () => {
    const data = { fullName: null, vehicle, documents: allDocs(), declaration: accepted }
    expect(STEPS[firstIncompleteIndex(data)].id).toBe('name')
    expect(countDone(data)).toBe(REQUIRED_STEPS.length - 1)
    expect(stepState(STEPS[0], { ...data, fullName: '   ' })).toBe('todo')
  })

  it('resumes on the first step still missing, skipping finished ones', () => {
    const docs = allDocs().filter((d) => d.type !== 'SELFIE')
    const index = firstIncompleteIndex({ fullName: 'Aisha Khan', vehicle, documents: docs, declaration: null })
    expect(STEPS[index].id).toBe('SELFIE')
  })

  it('treats a rejected RC photo as needing a fix on the vehicle step, and resumes there', () => {
    const data = { fullName: 'Aisha Khan', vehicle, documents: allDocs({ VEHICLE_REGISTRATION: { status: 'REJECTED' } }), declaration: accepted }
    const vehicleStep = STEPS.find((s) => s.id === 'vehicle')!
    expect(stepState(vehicleStep, data)).toBe('fix')
    expect(STEPS[firstIncompleteIndex(data)].id).toBe('vehicle')
  })

  it('needs both the vehicle number and the RC photo before the vehicle step is done', () => {
    const vehicleStep = STEPS.find((s) => s.id === 'vehicle')!
    expect(stepState(vehicleStep, { fullName: 'Aisha Khan', vehicle, documents: [], declaration: null })).toBe('todo')
    expect(stepState(vehicleStep, { fullName: 'Aisha Khan', vehicle: null, documents: allDocs(), declaration: null })).toBe('todo')
  })

  it('never blocks on the optional licence or fitness certificate', () => {
    const docs = allDocs().filter((d) => d.type === 'VEHICLE_REGISTRATION' || d.type === 'SELFIE')
    const data = { fullName: 'Aisha Khan', vehicle, documents: docs, declaration: accepted }
    expect(countDone(data)).toBe(REQUIRED_STEPS.length)
    expect(STEPS[firstIncompleteIndex(data)].kind).toBe('review')
  })

  it('has exactly the agreed steps: name first, then vehicle, optional licence, selfie, declaration (4 required)', () => {
    expect(WORK_STEPS.map((s) => s.id)).toEqual(['name', 'vehicle', 'LICENSE', 'SELFIE', 'declaration'])
    expect(REQUIRED_STEPS).toHaveLength(4)
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
    const data = { fullName: 'Aisha Khan', vehicle, documents: allDocs(), declaration: accepted }
    expect(countDone(data)).toBe(REQUIRED_STEPS.length)
    expect(STEPS[firstIncompleteIndex(data)].kind).toBe('review')
  })
})
