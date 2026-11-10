import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import NewPasswordFields from './NewPasswordFields'

describe('NewPasswordFields', () => {
  it('starts with the password hidden and "Show password" unticked, and toggles both fields', async () => {
    render(<NewPasswordFields password="" confirm="" onPassword={() => {}} onConfirm={() => {}} />)
    const toggle = screen.getByLabelText('Show password') as HTMLInputElement
    expect(toggle.checked).toBe(false)
    expect(screen.getByLabelText('New password')).toHaveAttribute('type', 'password')
    expect(screen.getByLabelText('Type it again')).toHaveAttribute('type', 'password')
    await userEvent.click(toggle)
    expect(screen.getByLabelText('New password')).toHaveAttribute('type', 'text')
  })
})
