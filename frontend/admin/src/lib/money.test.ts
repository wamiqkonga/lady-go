import { describe, expect, it } from 'vitest'
import { formatRupees, paiseToRupeesText } from './money'

describe('money', () => {
  it('formats paise as rupees', () => {
    expect(formatRupees(9000)).toBe('₹90.00')
    expect(formatRupees(5)).toBe('₹0.05')
    expect(formatRupees(123456789)).toBe('₹12,34,567.89')
    expect(formatRupees(0)).toBe('₹0.00')
  })
  it('shows a dash when there is no amount', () => {
    expect(formatRupees(undefined)).toBe('-')
    expect(formatRupees(null)).toBe('-')
  })
  it('does not use float maths for the text form', () => {
    expect(paiseToRupeesText(5550)).toBe('55.50')
    expect(paiseToRupeesText(-250)).toBe('-2.50')
  })
})
