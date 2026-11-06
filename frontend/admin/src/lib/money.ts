/** Money is whole paise on the wire (ADR-007). One formatter for the whole portal: 9000 -> "₹90.00". */

/** 5550 -> "55.50" (no float maths). */
export function paiseToRupeesText(paise: number): string {
  const sign = paise < 0 ? '-' : ''
  const abs = Math.abs(Math.trunc(paise))
  return `${sign}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}

/** 9000 -> "₹90.00"; undefined/null -> "-". Thousands are grouped Indian-style: 1234567 -> "₹12,345.67". */
export function formatRupees(paise: number | null | undefined): string {
  if (paise === undefined || paise === null || Number.isNaN(paise)) return '-'
  const text = paiseToRupeesText(paise)
  const negative = text.startsWith('-')
  const [whole, fraction] = (negative ? text.slice(1) : text).split('.')
  const grouped = new Intl.NumberFormat('en-IN').format(Number(whole))
  return `${negative ? '-' : ''}₹${grouped}.${fraction}`
}
