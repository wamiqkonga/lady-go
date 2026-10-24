import { describe, expect, it } from 'vitest'
import { unwrap, ApiCallError, querySerializer } from './client'

describe('unwrap', () => {
  it('returns the inner data on success', () => {
    const result = { data: { success: true, data: { id: '1' } }, response: new Response(null, { status: 200 }) }
    expect(unwrap(result)).toEqual({ id: '1' })
  })

  it('throws ApiCallError with the backend code/message on failure', () => {
    const result = {
      data: { success: false, data: null, error: { code: 'FORBIDDEN', message: 'Nope.' } },
      response: new Response(null, { status: 403 }),
    }
    expect(() => unwrap(result)).toThrow(ApiCallError)
    try {
      unwrap(result)
    } catch (err) {
      expect(err).toBeInstanceOf(ApiCallError)
      expect((err as ApiCallError).code).toBe('FORBIDDEN')
      expect((err as ApiCallError).status).toBe(403)
    }
  })
})

/** springdoc documents Spring's Pageable as one object-shaped `pageable` query param, but Spring's
 *  real binding expects flat page/size/sort - this is the fix for that mismatch. */
describe('querySerializer', () => {
  it('flattens a pageable object into page/size/sort', () => {
    const qs = querySerializer({ pageable: { page: 2, size: 50, sort: ['createdAt,desc'] } })
    expect(new URLSearchParams(qs).get('page')).toBe('2')
    expect(new URLSearchParams(qs).get('size')).toBe('50')
    expect(new URLSearchParams(qs).get('sort')).toBe('createdAt,desc')
  })

  it('passes other params through as plain key=value', () => {
    const qs = querySerializer({ status: 'RIDE_STARTED', driverId: undefined })
    expect(qs).toBe('status=RIDE_STARTED')
  })
})
