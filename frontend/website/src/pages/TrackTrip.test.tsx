import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TrackTrip from './TrackTrip'

function renderWithHash(hash: string) {
  window.location.hash = hash
  return render(
    <MemoryRouter>
      <TrackTrip />
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  window.location.hash = ''
})

describe('TrackTrip', () => {
  it('says the link is inactive without calling the server when there is no token', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderWithHash('')
    expect(screen.getByRole('alert')).toHaveTextContent('no longer active')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends the token in a header, never in the URL, and shows the trip', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        data: {
          driverFirstName: 'Asha',
          vehicleMake: 'Maruti',
          vehicleModel: 'Swift',
          vehicleColor: 'White',
          vehicleRegistration: 'KA01AB1234',
          latitude: 12.97,
          longitude: 77.59,
          locationAt: new Date().toISOString(),
        },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderWithHash('#tok-1')

    expect(await screen.findByText('Asha is driving')).toBeInTheDocument()
    expect(screen.getByText(/White Maruti Swift/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See her on the map' })).toHaveAttribute('rel', 'noopener noreferrer')
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).not.toContain('tok-1')
    expect((init as RequestInit).headers).toEqual({ 'X-Trip-Share-Token': 'tok-1' })
  })

  it('treats a rejected link as no longer active', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }))
    renderWithHash('#dead')
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer active')
  })
})
