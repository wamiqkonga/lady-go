import { describe, expect, it } from 'vitest'
import { cleanFullName, firstName, fullNameError } from './fullName'

describe('fullNameError', () => {
  it.each([
    'Aisha Khan', 'Mary-Anne O\'Neil', 'Dr. A. R. Mir', 'Ab', 'عائشہ خان', 'आयशा खान', 'कश्मीर सिंह', 'Zoë Müller',
  ])('accepts %s', (name) => expect(fullNameError(name)).toBeNull())

  it.each(['', '   ', 'A', 'Aisha 2', '1234', '<b>Aisha</b>', 'Aisha_Khan', 'Aisha@Khan', '😀 Aisha', '-', ". .", "'''", 'a'.repeat(81)])(
    'rejects %j', (name) => expect(fullNameError(name)).not.toBeNull())

  it('allows exactly 80 characters after collapsing spaces', () => {
    expect(fullNameError('a'.repeat(40) + '     ' + 'a'.repeat(39))).toBeNull()
    expect(fullNameError('a'.repeat(80))).toBeNull()
  })
})

describe('cleanFullName and firstName', () => {
  it('trims and collapses repeated spaces', () => expect(cleanFullName('  Aisha    Khan ')).toBe('Aisha Khan'))
  it('takes the first word as the first name', () => {
    expect(firstName('Aisha Khan')).toBe('Aisha')
    expect(firstName('  عائشہ  خان')).toBe('عائشہ')
    expect(firstName(null)).toBeNull()
    expect(firstName('   ')).toBeNull()
  })
})
