/** Fares are whole paise on the wire (ADR-007). The screen takes rupees and converts with string maths only. */

export type ParsedRupees = { ok: true; paise: number } | { ok: false; error: string }

const RUPEES = /^(\d{1,7})(?:\.(\d{1,2}))?$/

/** "55" -> 5500, "55.5" -> 5550, "55.05" -> 5505. Three decimals, a minus sign, letters or "1e3" are refused. */
export function rupeesToPaise(text: string): ParsedRupees {
  const trimmed = text.trim()
  if (trimmed === '') return { ok: false, error: 'Enter an amount.' }
  if (/^\d+\.\d{3,}$/.test(trimmed)) return { ok: false, error: 'Use at most two decimals (whole paise only).' }
  const match = RUPEES.exec(trimmed)
  if (!match) return { ok: false, error: 'Enter rupees like 55 or 55.50.' }
  const whole = Number(match[1])
  const fraction = Number((match[2] ?? '').padEnd(2, '0'))
  return { ok: true, paise: whole * 100 + fraction }
}

/** 5550 -> "55.50" (no float maths). */
export function paiseToRupeesText(paise: number): string {
  const whole = Math.trunc(paise / 100)
  const fraction = String(paise % 100).padStart(2, '0')
  return `${whole}.${fraction}`
}

export function formatRupees(paise: number | undefined): string {
  return paise === undefined ? '-' : `Rs ${paiseToRupeesText(paise)}`
}

/** Percent with at most two decimals, as a number. */
export function parsePercent(text: string): { ok: true; value: number } | { ok: false; error: string } {
  const trimmed = text.trim()
  if (trimmed === '') return { ok: false, error: 'Enter a percentage.' }
  if (/^\d+\.\d{3,}$/.test(trimmed)) return { ok: false, error: 'Use at most two decimals.' }
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(trimmed)) return { ok: false, error: 'Enter a percentage like 5 or 7.5.' }
  return { ok: true, value: Number(trimmed) }
}
