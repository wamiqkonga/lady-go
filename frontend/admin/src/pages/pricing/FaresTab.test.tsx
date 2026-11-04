import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionContext } from '../../lib/sessionContext'
import { StepUpProvider } from '../../lib/StepUpProvider'
import Pricing from './Pricing'

afterEach(() => vi.restoreAllMocks())

const CITY = 'city-1'
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const ok = (data: unknown) => json({ success: true, data, error: null })
const fail = (code: string, message: string, status: number) => json({ success: false, data: null, error: { code, message } }, status)

const limits = {
  baseFareMaxPaise: 50000, perKmMaxPaise: 10000, perMinuteMaxPaise: 2000, minimumFareMaxPaise: 100000,
  cancellationFeeMaxPaise: 50000, serviceFeeFlatMaxPaise: 10000, serviceFeePctMax: 30,
}
const rule = (over: Record<string, unknown> = {}) => ({
  id: 'r1', cityId: CITY, vehicleType: 'BIKE', currency: 'INR', baseFare: 3000, perKm: 1000, perMinute: 100,
  minimumFare: 4000, cancellationFee: 2000, serviceFeePct: 5, serviceFeeFlat: 0, status: 'IN_FORCE',
  effectiveFrom: '2026-11-01T00:00:00Z', cancellable: false, ...over,
})
const viewOf = (over: Record<string, unknown> = {}) => ({
  cityId: CITY, vehicleType: 'BIKE', current: rule(), scheduled: null, latestRuleId: 'r1', history: [rule()], limits,
  largeChangePct: 50, ...over,
})
const previewOf = (large: unknown[] = []) => ({
  samples: [
    { distanceMeters: 2000, durationSeconds: 480, current: { totalFare: 5000 }, proposed: { totalFare: 5600 } },
    { distanceMeters: 5000, durationSeconds: 900, current: { totalFare: 9000 }, proposed: { totalFare: 9900 } },
  ],
  largeChanges: large,
})

type Call = { method: string; path: string; body: unknown }
type Handler = (call: Call) => Response | undefined

/** Routes every request by method + path; records each call so a test can assert exactly what was sent. */
function mockApi(handler: Handler, cities = [{ id: CITY, name: 'Srinagar', active: true, hasServiceArea: true, vehicleTypesWithFare: ['BIKE'] }]) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string, init?: RequestInit) => {
    const request = typeof input === 'string' ? new Request(input, init) : input
    const url = new URL(request.url)
    const text = request.method === 'GET' ? '' : await request.clone().text()
    const call: Call = { method: request.method, path: url.pathname + url.search, body: text ? JSON.parse(text) : undefined }
    calls.push(call)
    const custom = handler(call)
    if (custom) return custom
    if (call.path.startsWith('/api/v1/admin/pricing-rules/cities')) return ok(cities)
    if (call.path.startsWith('/api/v1/admin/pricing-rules?')) return ok(viewOf())
    if (call.path.endsWith('/preview')) return ok(previewOf())
    return fail('NOT_MOCKED', `${call.method} ${call.path}`, 500)
  }))
  return calls
}

function renderPricing(role: 'ADMIN' | 'SUPER_ADMIN' | 'SUPPORT' = 'SUPER_ADMIN', path = '/pricing') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
        <StepUpProvider>
          <Routes>
            <Route path="pricing/*" element={<Pricing />} />
          </Routes>
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

const field = (label: RegExp | string) => screen.getByLabelText(label)

async function fillAll(user: ReturnType<typeof userEvent.setup>, v: Record<string, string>) {
  for (const [label, value] of Object.entries(v)) {
    const input = field(label)
    await user.clear(input)
    await user.type(input, value)
  }
}

const GOOD = {
  'Base fare (rupees)': '30', 'Per kilometre (rupees)': '10', 'Per minute (rupees)': '1',
  'Minimum fare (rupees)': '40', 'Cancellation fee (rupees)': '20', 'Service fee, flat (rupees)': '0', 'Service fee (percent)': '5',
}

describe('Pricing tabs', () => {
  it('shows Fares and Settings tabs to a SUPER_ADMIN, Fares first', async () => {
    mockApi(() => undefined)
    renderPricing()
    const tabs = within(screen.getByRole('navigation', { name: 'Pricing sections' }))
    expect(tabs.getAllByRole('link').map((a) => a.textContent)).toEqual(['Fares', 'Settings'])
    await screen.findByRole('region', { name: 'Two-wheeler fare' })
  })

  it('an ADMIN sees only Settings and never calls the fares endpoints', async () => {
    const calls = mockApi((c) => (c.path.startsWith('/api/v1/admin/config') ? ok([]) : undefined))
    renderPricing('ADMIN')
    const tabs = within(screen.getByRole('navigation', { name: 'Pricing sections' }))
    expect(tabs.queryByRole('link', { name: 'Fares' })).not.toBeInTheDocument()
    await screen.findByText(/Pricing & configuration/)
    expect(calls.some((c) => c.path.includes('pricing-rules'))).toBe(false)
  })

  it('the Settings tab is the existing configuration screen', async () => {
    mockApi((c) => (c.path.startsWith('/api/v1/admin/config') ? ok([{ id: 'c', configKey: 'driver.search.radius.m', configValue: '5000', valueType: 'INTEGER', scope: 'GLOBAL' }]) : undefined))
    renderPricing('SUPER_ADMIN', '/pricing/settings')
    expect(await screen.findByText('driver.search.radius.m')).toBeInTheDocument()
  })
})

describe('Fares tab: reading', () => {
  it('shows the current fare per vehicle type in rupees with since-date, and only BIKE by default', async () => {
    mockApi(() => undefined)
    renderPricing()
    const card = await screen.findByRole('region', { name: 'Two-wheeler fare' })
    expect(within(card).getAllByText('Rs 30.00').length).toBeGreaterThan(0)
    expect(within(card).getAllByText('Rs 40.00').length).toBeGreaterThan(0)
    expect(within(card).getByText(/In force since/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Cab fare' })).not.toBeInTheDocument()
  })

  it('also shows another vehicle type once it already has a fare', async () => {
    mockApi(
      (c) => (c.path.includes('vehicleType=CAB') ? ok(viewOf({ vehicleType: 'CAB' })) : undefined),
      [{ id: CITY, name: 'Srinagar', active: true, hasServiceArea: true, vehicleTypesWithFare: ['BIKE', 'CAB'] }],
    )
    renderPricing()
    expect(await screen.findByRole('region', { name: 'Cab fare' })).toBeInTheDocument()
  })

  it('warns when the city has no service area', async () => {
    mockApi(() => undefined, [{ id: CITY, name: 'Srinagar', active: true, hasServiceArea: false, vehicleTypesWithFare: ['BIKE'] }])
    renderPricing()
    expect(await screen.findByText(/no service area set/)).toBeInTheDocument()
  })

  it('shows the red "No fare set" state with a Set the first fare button when no fare exists', async () => {
    mockApi((c) => (c.path.startsWith('/api/v1/admin/pricing-rules?') ? ok(viewOf({ current: null, latestRuleId: null, history: [] })) : undefined))
    renderPricing()
    expect(await screen.findByText('No fare set: riders cannot book')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set the first fare' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change fare' })).not.toBeInTheDocument()
  })

  it('lists history newest first as a table that is labelled per cell for the phone card layout', async () => {
    mockApi((c) => (c.path.startsWith('/api/v1/admin/pricing-rules?')
      ? ok(viewOf({ history: [rule(), rule({ id: 'r0', status: 'ENDED', baseFare: 2500, effectiveTo: '2026-11-01T00:00:00Z' })] }))
      : undefined))
    renderPricing()
    const table = await screen.findByRole('table', { name: 'Fare history' })
    expect(table).toHaveClass('fare-history')
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(within(rows[1]).getByText('In force')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Ended')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Rs 25.00')).toHaveAttribute('data-label', 'Base fare')
  })
})

describe('Fares tab: setting and changing a fare', () => {
  it('sets the first fare: converts rupees to exact paise, previews, asks a reason, sends no expected rule', async () => {
    const user = userEvent.setup()
    const calls = mockApi((c) => {
      if (c.path.startsWith('/api/v1/admin/pricing-rules?')) return ok(viewOf({ current: null, latestRuleId: null, history: [] }))
      if (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules') return json(ok(rule()), 201)
      return undefined
    })
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Set the first fare' }))
    await fillAll(user, { ...GOOD, 'Base fare (rupees)': '30.5', 'Service fee (percent)': '7.25' })

    await screen.findByRole('table', { name: 'Sample fares' })
    const preview = calls.filter((c) => c.path.endsWith('/preview')).at(-1)!
    expect(preview.body).toMatchObject({ cityId: CITY, vehicleType: 'BIKE', baseFare: 3050, perKm: 1000, perMinute: 100, minimumFare: 4000, cancellationFee: 2000, serviceFeeFlat: 0, serviceFeePct: 7.25 })

    await user.click(screen.getByRole('button', { name: 'Review change' }))
    const summary = screen.getByRole('table', { name: 'Change summary' })
    expect(within(summary).getByText('Rs 30.50')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save fare' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason (required)'), 'launch fare')
    await user.click(screen.getByRole('button', { name: 'Save fare' }))

    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')).toBe(true))
    const sent = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')!.body as Record<string, unknown>
    expect(sent).toMatchObject({ baseFare: 3050, reason: 'launch fare', confirmLargeChange: false })
    expect(sent.expectedLatestRuleId).toBeUndefined()
    expect(sent.effectiveFrom).toBeUndefined()
  })

  it('refuses a rupee value with three decimals, shows why, and never previews or saves it', async () => {
    const user = userEvent.setup()
    const calls = mockApi(() => undefined)
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '30.555')

    expect(await screen.findByText('Use at most two decimals (whole paise only).')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled()
    await new Promise((r) => setTimeout(r, 450))
    expect(calls.some((c) => c.path.endsWith('/preview') && (c.body as { baseFare: number }).baseFare === 3055)).toBe(false)
  })

  it('shows the side-by-side sample fares from the server preview', async () => {
    const user = userEvent.setup()
    mockApi(() => undefined)
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '31')
    const table = await screen.findByRole('table', { name: 'Sample fares' })
    expect(within(table).getByText('2 km / 8 min')).toBeInTheDocument()
    expect(within(table).getByText('Rs 50.00')).toBeInTheDocument()
    expect(within(table).getByText('Rs 56.00')).toBeInTheDocument()
  })

  it('a large change shows the red warning with old and new numbers and needs the confirm tick before Review', async () => {
    const user = userEvent.setup()
    const calls = mockApi((c) => {
      if (c.path.endsWith('/preview')) return ok(previewOf([{ field: 'baseFare', oldValue: '3000', newValue: '9000' }]))
      if (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules') return json(ok(rule()), 201)
      return undefined
    })
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '90')

    const warning = await screen.findByText(/Large change: more than 50 percent/)
    expect(warning.closest('[role="alert"]')).toHaveClass('fare-warning')
    expect(screen.getByText(/Base fare: Rs 30.00 to Rs 90.00/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled()

    await user.click(screen.getByLabelText('I confirm this large change'))
    await user.click(screen.getByRole('button', { name: 'Review change' }))
    await user.type(screen.getByLabelText('Reason (required)'), 'peak pricing')
    await user.click(screen.getByRole('button', { name: 'Save fare' }))

    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')).toBe(true))
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')!.body).toMatchObject({
      baseFare: 9000, confirmLargeChange: true, expectedLatestRuleId: 'r1',
    })
  })

  it('the confirmation shows old to new for every field', async () => {
    const user = userEvent.setup()
    mockApi(() => undefined)
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Per kilometre (rupees)'))
    await user.type(field('Per kilometre (rupees)'), '12')
    await screen.findByRole('table', { name: 'Sample fares' })
    await user.click(screen.getByRole('button', { name: 'Review change' }))
    const row = within(screen.getByRole('table', { name: 'Change summary' })).getByText('Per kilometre').closest('tr')!
    expect(within(row).getByText('Rs 10.00')).toBeInTheDocument()
    expect(within(row).getByText('Rs 12.00')).toBeInTheDocument()
    expect(screen.getByText('now')).toBeInTheDocument()
  })

  it('a future start is sent as an ISO time and a past or empty one blocks Review', async () => {
    const user = userEvent.setup()
    const calls = mockApi((c) => (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules' ? json(ok(rule()), 201) : undefined))
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '31')
    await screen.findByRole('table', { name: 'Sample fares' })
    await user.click(screen.getByLabelText('At a later time'))
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled()
    const start = field('Start time') as HTMLInputElement
    await user.type(start, '2020-01-01T10:00')
    expect(screen.getByText('Choose a time in the future.')).toBeInTheDocument()
    await user.clear(start)
    await user.type(start, '2099-01-01T10:00')
    await user.click(screen.getByRole('button', { name: 'Review change' }))
    await user.type(screen.getByLabelText('Reason (required)'), 'weekend')
    await user.click(screen.getByRole('button', { name: 'Save fare' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')).toBe(true))
    const sent = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')!.body as { effectiveFrom: string }
    expect(sent.effectiveFrom).toMatch(/^2099-01-01T\d\d:00:00\.000Z$/)
  })

  it('a server limit error from the preview is shown and blocks Review', async () => {
    const user = userEvent.setup()
    mockApi((c) => (c.path.endsWith('/preview') ? fail('VALIDATION_FAILED', 'Base fare must be between 0 and 50000 paise.', 400) : undefined))
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '600')
    expect(await screen.findByText('Base fare must be between 0 and 50000 paise.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled()
  })

  it('asks for the authenticator code when the server demands a step-up, then retries once', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const calls = mockApi((c) => {
      if (c.path.includes('/auth/') && c.path.includes('step-up')) return ok({ accessToken: 'tok', refreshToken: 'r' })
      if (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules') {
        attempts += 1
        return attempts === 1 ? fail('STEP_UP_REQUIRED', 'Confirm your identity', 403) : json(ok(rule()), 201)
      }
      return undefined
    })
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '31')
    await screen.findByRole('table', { name: 'Sample fares' })
    await user.click(screen.getByRole('button', { name: 'Review change' }))
    await user.type(screen.getByLabelText('Reason (required)'), 'why')
    await user.click(screen.getByRole('button', { name: 'Save fare' }))

    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Authenticator code'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(attempts).toBe(2))
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules')).toHaveLength(2)
  })

  it('shows the server message when someone else changed the fare meanwhile', async () => {
    const user = userEvent.setup()
    mockApi((c) => (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules'
      ? fail('CONCURRENT_UPDATE', 'The fare was changed by someone else. Reload and review it before changing it.', 409)
      : undefined))
    renderPricing()
    await user.click(await screen.findByRole('button', { name: 'Change fare' }))
    await user.clear(field('Base fare (rupees)'))
    await user.type(field('Base fare (rupees)'), '31')
    await screen.findByRole('table', { name: 'Sample fares' })
    await user.click(screen.getByRole('button', { name: 'Review change' }))
    await user.type(screen.getByLabelText('Reason (required)'), 'why')
    await user.click(screen.getByRole('button', { name: 'Save fare' }))
    expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument()
  })
})

describe('Fares tab: scheduled change', () => {
  const scheduled = rule({ id: 'r2', status: 'SCHEDULED', baseFare: 3500, effectiveFrom: '2099-01-01T00:00:00Z', cancellable: true })

  it('shows the scheduled banner, hides Change fare, and cancels with a reason', async () => {
    const user = userEvent.setup()
    const calls = mockApi((c) => {
      if (c.path.startsWith('/api/v1/admin/pricing-rules?')) return ok(viewOf({ scheduled, latestRuleId: 'r2', history: [scheduled, rule()] }))
      if (c.method === 'POST' && c.path === '/api/v1/admin/pricing-rules/r2/cancel') return ok(scheduled)
      return undefined
    })
    renderPricing()
    expect(await screen.findByText('Scheduled change')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change fare' })).not.toBeInTheDocument()
    expect(screen.getByText(/cancel the scheduled change first/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cancel scheduled change' }))
    const confirm = screen.getByRole('button', { name: 'Confirm cancel scheduled change' })
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText('Reason (required)'), 'changed my mind')
    await user.click(confirm)
    await waitFor(() => expect(calls.some((c) => c.path.endsWith('/r2/cancel'))).toBe(true))
    expect(calls.find((c) => c.path.endsWith('/r2/cancel'))!.body).toEqual({ reason: 'changed my mind' })
  })

  it('does not offer Cancel when a booking already uses the scheduled fare', async () => {
    mockApi((c) => (c.path.startsWith('/api/v1/admin/pricing-rules?')
      ? ok(viewOf({ scheduled: { ...scheduled, cancellable: false }, latestRuleId: 'r2' }))
      : undefined))
    renderPricing()
    expect(await screen.findByText(/already uses this fare/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel scheduled change' })).not.toBeInTheDocument()
  })

  it('shows a scheduled first fare without the red no-fare alarm', async () => {
    mockApi((c) => (c.path.startsWith('/api/v1/admin/pricing-rules?')
      ? ok(viewOf({ current: null, scheduled, latestRuleId: 'r2', history: [scheduled] }))
      : undefined))
    renderPricing()
    expect(await screen.findByText('Scheduled change')).toBeInTheDocument()
    expect(screen.queryByText('No fare set: riders cannot book')).not.toBeInTheDocument()
  })
})
