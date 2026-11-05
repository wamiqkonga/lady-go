import type { ReactElement } from 'react'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import Contact from './Contact'
import Faq from './Faq'
import Privacy from './Privacy'
import Terms from './Terms'

function text(el: ReactElement) {
  const { container } = render(<MemoryRouter>{el}</MemoryRouter>)
  return container.textContent ?? ''
}

describe('public content pages', () => {
  it('Contact shows the support address as a mailto link and the 112 note', () => {
    render(<MemoryRouter><Contact /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'supportladygo@gmail.com' })).toHaveAttribute('href', 'mailto:supportladygo@gmail.com')
    expect(screen.getByText(/call 112 first/i)).toBeInTheDocument()
  })

  it('Privacy and the FAQ list the captain\'s full name among the registration details', () => {
    expect(text(<Privacy />)).toMatch(/Your full name/)
    render(<MemoryRouter><Faq /></MemoryRouter>)
    expect(screen.getByText(/Your full name as it appears on your driving licence or RC/)).toBeInTheDocument()
  })

  it('FAQ has answers about the pilot terms and expandable questions', () => {
    render(<MemoryRouter><Faq /></MemoryRouter>)
    expect(screen.getByText('Is Lady Go my employer?')).toBeInTheDocument()
    expect(screen.getByText(/paid in cash/i)).toBeInTheDocument()
    expect(screen.getByText(/Two-wheelers only/i)).toBeInTheDocument()
  })

  it.each([
    ['Terms', <Terms key="t" />],
    ['Privacy', <Privacy key="p" />],
    ['FAQ', <Faq key="f" />],
    ['Contact', <Contact key="c" />],
  ])('%s carries no placeholder or owner-only text and no invented company facts', (_name, el) => {
    const t = text(el)
    expect(t).not.toMatch(/TODO|draft placeholder|GSTIN|CIN\b|registered office/i)
    expect(t).not.toMatch(/₹|\d+\s?%|within \d+/i) // no fares, commission or response-time promises
  })

  it('Terms say Lady Go is a platform, not an employer, and does not inspect vehicles', () => {
    render(<MemoryRouter><Terms /></MemoryRouter>)
    expect(screen.getByText(/not the employer of any captain/i)).toBeInTheDocument()
    expect(screen.getByText(/does not own, inspect or guarantee/i)).toBeInTheDocument()
  })
})
