import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog, ReasonDialog } from './ReasonDialog'

describe('ReasonDialog', () => {
  it('is a labelled modal that traps focus and closes on Escape', async () => {
    const onCancel = vi.fn()
    render(<ReasonDialog title="Reject this driver" confirmLabel="Reject driver" onConfirm={vi.fn()} onCancel={onCancel} />)
    const dialog = screen.getByRole('dialog', { name: 'Reject this driver' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByLabelText('Reason (required)')).toHaveFocus()
    const user = userEvent.setup()
    await user.tab()
    await user.tab()
    await user.tab()
    await user.tab()
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
    await user.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
  })

  it('refuses an empty reason with a visible error and sends a trimmed one', async () => {
    const onConfirm = vi.fn()
    const user = userEvent.setup()
    render(<ReasonDialog title="Reject" confirmLabel="Reject driver" requiredMessage="Write a reason." onConfirm={onConfirm} onCancel={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Reject driver' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Write a reason.')
    expect(screen.getByLabelText('Reason (required)')).toHaveAccessibleDescription('Write a reason.')
    expect(onConfirm).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Reason (required)'), '  blurry photo ')
    await user.click(screen.getByRole('button', { name: 'Reject driver' }))
    expect(onConfirm).toHaveBeenCalledWith('blurry photo')
  })

  it('returns focus to the opener when it closes', async () => {
    function Host() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>Open</button>
          {open && <ConfirmDialog title="Sure?" description="This cannot be undone." confirmLabel="Yes" onConfirm={() => setOpen(false)} onCancel={() => setOpen(false)} />}
        </>
      )
    }
    const user = userEvent.setup()
    render(<Host />)
    await user.click(screen.getByRole('button', { name: 'Open' }))
    expect(screen.getByRole('dialog', { name: 'Sure?' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus()
  })
})
