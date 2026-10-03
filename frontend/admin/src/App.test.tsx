import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('admin routes', () => {
  it('redirects the root to the dashboard', () => {
    renderAt('/')
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
  })

  it('shows every dashboard tile from the admin flow spec without fake numbers', () => {
    renderAt('/dashboard')
    expect(screen.getByLabelText('Active drivers: no data yet')).toBeInTheDocument()
    expect(screen.getByLabelText('System alerts: no data yet')).toBeInTheDocument()
  })

  it.each([
    ['/live-map', 'Live map'],
    ['/rides', 'Rides'],
    ['/drivers', 'Drivers'],
    ['/driver-verification', 'Driver verification'],
    ['/customers', 'Customers'],
    ['/pricing', 'Pricing and configuration'],
    ['/safety', 'Safety incidents'],
    ['/audit-logs', 'Audit log'],
  ])('renders %s', (path, heading) => {
    renderAt(path)
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
  })

  it('states clearly that the user is not signed in', () => {
    renderAt('/dashboard')
    expect(screen.getByRole('status')).toHaveTextContent('Not signed in')
  })

  it('keeps sign-in disabled until authentication exists', () => {
    renderAt('/login')
    expect(screen.getByLabelText('Email')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
  })
})
