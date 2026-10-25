import { describe, expect, it } from 'vitest'
import { normalizePhone } from './phone'

describe('normalizePhone', () => {
  it.each([
    ['9086660267', '+919086660267'],
    ['90866 60267', '+919086660267'],
    ['90866-60267', '+919086660267'],
    ['09086660267', '+919086660267'],
    ['919086660267', '+919086660267'],
    ['+91 90866 60267', '+919086660267'],
    ['+919086660267', '+919086660267'],
    ['00919086660267', '+919086660267'],
    ['(+91) 9086660267', '+919086660267'],
  ])('turns %s into %s', (typed, expected) => {
    expect(normalizePhone(typed)).toEqual({ phone: expected })
  })

  it('keeps a valid number from another country as typed', () => {
    expect(normalizePhone('+447911123456')).toEqual({ phone: '+447911123456' })
  })

  it.each([
    ['', 'empty'],
    ['12345', 'too short'],
    ['5086660267', 'a 10-digit Indian mobile starts with 6, 7, 8 or 9'],
    ['90866602671', '11 digits that do not start with 0'],
    ['abcdefghij', 'letters'],
    ['+0123456789', 'country code cannot start with 0'],
  ])('rejects %s (%s) with a helpful message', (typed) => {
    const result = normalizePhone(typed)
    expect(result).toHaveProperty('error')
    expect((result as { error: string }).error).toMatch(/10-digit mobile number/)
  })
})
