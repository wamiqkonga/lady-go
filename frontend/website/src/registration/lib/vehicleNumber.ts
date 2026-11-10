/** Mirrors the server's RegistrationNumbers rules (the server stays authoritative): spaces/hyphens
 *  allowed, any case, stored as upper case with no separators. Standard (JK01AB1234) or Bharat
 *  series (22BH1234AA). */
const STANDARD = /^[A-Z]{2}\d{1,2}[A-Z]{0,3}\d{4}$/
const BHARAT = /^\d{2}BH\d{4}[A-HJ-NP-Z]{1,2}$/

export const VEHICLE_NUMBER_MESSAGE = 'Enter a valid vehicle number, for example JK01AB1234.'

export function normaliseVehicleNumber(raw: string): string {
  return raw.replace(/[\s-]/g, '').toUpperCase()
}

export function vehicleNumberError(raw: string): string | null {
  const value = normaliseVehicleNumber(raw)
  return STANDARD.test(value) || BHARAT.test(value) ? null : VEHICLE_NUMBER_MESSAGE
}
