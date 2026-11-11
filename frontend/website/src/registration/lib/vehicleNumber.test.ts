import { describe, expect, it } from 'vitest'
import { normaliseVehicleNumber, vehicleNumberError } from './vehicleNumber'

describe('vehicleNumberError', () => {
  it.each(['JK01AB1234', 'jk01ab1234', 'JK 01 AB 1234', 'jk-01-ab-1234', 'JK011234', 'DL1CAB1234', 'KA05MN9999', '22BH1234AA', '22bh1234a'])(
    'accepts %s', (v) => expect(vehicleNumberError(v)).toBeNull())

  it.each(['', 'abc', 'JK01AB123', 'JK01AB12345', '1234', 'JKK01AB1234', 'JK01ABCD1234', '22BH1234IO', '22BH1234', '!!JK01AB1234'])(
    'rejects %j', (v) => expect(vehicleNumberError(v)).toBe('Enter a valid vehicle number, for example JK01AB1234.'))
})

describe('normaliseVehicleNumber', () => {
  it('uppercases and strips spaces and hyphens', () => {
    expect(normaliseVehicleNumber(' jk-01 ab 1234 ')).toBe('JK01AB1234')
  })
})
