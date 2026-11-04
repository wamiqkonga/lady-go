import { describe, expect, it } from 'vitest'
import { formatRupees, paiseToRupeesText, parsePercent, rupeesToPaise } from './money'

describe('rupeesToPaise', () => {
  it.each([
    ['55', 5500], ['55.5', 5550], ['55.05', 5505], ['0', 0], ['0.01', 1], ['1234567.99', 123456799], [' 12 ', 1200],
  ])('converts %s exactly to %i paise', (text, paise) => {
    expect(rupeesToPaise(text)).toEqual({ ok: true, paise })
  })

  it('never goes through floating point (19.99 and 1.1 are exact)', () => {
    expect(rupeesToPaise('19.99')).toEqual({ ok: true, paise: 1999 })
    expect(rupeesToPaise('1.10')).toEqual({ ok: true, paise: 110 })
  })

  it.each(['55.555', '0.001', '1e3', '-5', '', 'abc', '5,50', '.5', '5.', '12345678'])('refuses %j', (text) => {
    expect(rupeesToPaise(text).ok).toBe(false)
  })

  it('explains a third decimal', () => {
    expect(rupeesToPaise('5.555')).toEqual({ ok: false, error: 'Use at most two decimals (whole paise only).' })
  })
})

describe('formatting', () => {
  it('formats paise as rupees', () => {
    expect(paiseToRupeesText(5)).toBe('0.05')
    expect(formatRupees(5550)).toBe('Rs 55.50')
    expect(formatRupees(undefined)).toBe('-')
  })

  it('parses percentages with at most two decimals', () => {
    expect(parsePercent('7.25')).toEqual({ ok: true, value: 7.25 })
    expect(parsePercent('7.255').ok).toBe(false)
    expect(parsePercent('-1').ok).toBe(false)
    expect(parsePercent('').ok).toBe(false)
  })
})
