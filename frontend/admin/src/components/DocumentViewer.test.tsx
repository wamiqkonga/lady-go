import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentViewer from './DocumentViewer'

afterEach(() => vi.restoreAllMocks())

function mockSignedUrls() {
  let n = 0
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
    n += 1
    return new Response(JSON.stringify({ success: true, data: `https://files.example/doc.jpg?sig=${n}` }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
}

describe('DocumentViewer', () => {
  it('shows a plain error with Retry when the photo fails to load, and Retry requests a fresh URL', async () => {
    const fetchMock = mockSignedUrls()
    const user = userEvent.setup()
    render(<DocumentViewer documentId="doc-1" title="Selfie" />)
    await user.click(screen.getByRole('button', { name: 'View Selfie' }))
    const img = await screen.findByRole('img', { name: 'Selfie' })
    expect(img).toHaveAttribute('src', 'https://files.example/doc.jpg?sig=1')

    fireEvent.error(img)
    expect(screen.queryByRole('img', { name: 'Selfie' })).toBeNull()
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load this photo. Try again.')

    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('img', { name: 'Selfie' })).toHaveAttribute('src', 'https://files.example/doc.jpg?sig=2')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
