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
    ['/driver', 'Earn ₹30,000+ a month with us'],
    ['/driver/login', 'Driver registration'],
    ['/contact', 'Contact us'],
    ['/faq', 'Frequently asked questions'],
    ['/terms', 'Terms of service'],
    ['/privacy', 'Privacy policy'],
  ]

  it.each(routes)('renders %s', (path, heading) => {
    renderAt(path)
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
  })

  it('shows a not-found page for unknown routes', () => {
    renderAt('/nope')
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
  })

  it('redirects /login to the driver registration login', () => {
    renderAt('/login')
    expect(screen.getByRole('heading', { level: 1, name: 'Driver registration' })).toBeInTheDocument()
    expect(screen.getByLabelText('Phone number')).toBeEnabled()
  })

  it('sends signed-out visitors from /driver/register to the login', () => {
    renderAt('/driver/register')
    expect(screen.getByRole('heading', { level: 1, name: 'Driver registration' })).toBeInTheDocument()
  })

  it('advertises driver earnings on the homepage and links to registration', () => {
    renderAt('/')
    expect(screen.getByRole('heading', { level: 2, name: /₹30,000\+/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Register as a driver' })).toHaveAttribute('href', '/driver')
  })

  it('toggles the mobile menu', async () => {
    renderAt('/')
    const toggle = screen.getByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('switches between light and dark mode and remembers the choice', async () => {
    renderAt('/')
    await userEvent.click(screen.getByRole('button', { name: /Switch to (light|dark) mode/ }))
    const chosen = document.documentElement.dataset.theme
    expect(['light', 'dark']).toContain(chosen)
    expect(localStorage.getItem('ladygo-theme')).toBe(chosen)
  })
})
