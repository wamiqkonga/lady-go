import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

describe('website routes', () => {
  const routes: Array<[string, string]> = [
    ['/', 'Safe, simple rides for women'],
    ['/about', 'About Lady Go'],
    ['/how-it-works', 'How it works'],
    ['/safety', 'Safety'],
    ['/driver', 'Drive with Lady Go'],
    ['/contact', 'Contact us'],
    ['/faq', 'Frequently asked questions'],
    ['/terms', 'Terms of service'],
    ['/privacy', 'Privacy policy'],
    ['/login', 'Log in'],
  ]

  it.each(routes)('renders %s', (path, heading) => {
    renderAt(path)
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
  })

  it('shows a not-found page for unknown routes', () => {
    renderAt('/nope')
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
  })

  it('does not enable login before authentication exists', () => {
    renderAt('/login')
    expect(screen.getByLabelText('Phone number')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Send code' })).toBeDisabled()
  })

  it('toggles the mobile menu', async () => {
    renderAt('/')
    const toggle = screen.getByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })
})
