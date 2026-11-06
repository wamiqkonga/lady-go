import { describe, expect, it } from 'vitest'
import { actionLabel, detailPairs, targetLabel } from './audit'

describe('audit wording', () => {
  it('names known actions in plain words and falls back to sentence case', () => {
    expect(actionLabel('DRIVER_APPROVED')).toBe('Approved a driver')
    expect(actionLabel('SOMETHING_NEW_HAPPENED')).toBe('Something new happened')
    expect(targetLabel('driver_documents')).toBe('Driver document')
  })

  it('turns metadata into readable pairs, with money in rupees', () => {
    const pairs = detailPairs({
      reason: 'Complaint verified',
      outcome: 'POLICE_INFORMED',
      largeChangeConfirmed: true,
      oldValues: { baseFare: 3000, serviceFeePct: '5.00' },
      newValues: { baseFare: 3500, serviceFeePct: '5.00' },
    })
    expect(pairs).toEqual([
      { label: 'Reason', value: 'Complaint verified' },
      { label: 'Outcome', value: 'Police informed' },
      { label: 'Large change confirmed', value: 'Yes' },
      { label: 'Before', value: 'Base fare: ₹30.00 · Service fee (%): 5.00' },
      { label: 'After', value: 'Base fare: ₹35.00 · Service fee (%): 5.00' },
    ])
    expect(detailPairs(null)).toEqual([])
  })
})
