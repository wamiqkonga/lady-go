import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SelfieCapture from './SelfieCapture'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function jpegFile() {
  return new File([new Uint8Array(1024)], 'selfie.jpg', { type: 'image/jpeg' })
}

describe('SelfieCapture', () => {
  it('accepts a selfie via the file-input fallback (camera never engaged)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ success: true, data: { id: 'doc-1', type: 'SELFIE', status: 'PENDING' }, error: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const onUploaded = vi.fn()

    render(<SelfieCapture onUploaded={onUploaded} />)

    // The file input is always offered alongside "Use camera" - never only reachable after a camera
    // failure - so this never calls getUserMedia at all.
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [jpegFile()] } })

    await waitFor(() => expect(screen.getByAltText('Selfie preview')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }))

    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toContain('/drivers/me/selfie')
  })

  it('rejects a non-image file from the fallback input', () => {
    vi.stubGlobal('fetch', vi.fn())

    render(<SelfieCapture onUploaded={vi.fn()} />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const pdf = new File(['x'], 'selfie.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [pdf] } })

    expect(screen.getByRole('alert')).toHaveTextContent('Only JPEG or PNG images are accepted.')
  })

  it('shows a rejection note as plain text, never markup', () => {
    render(
      <SelfieCapture
        existing={{ id: 'doc-1', type: 'SELFIE', status: 'REJECTED', reviewNote: '<img src=x onerror=alert(1)>face not visible' }}
        onUploaded={vi.fn()}
      />,
    )

    expect(screen.getByText(/face not visible/)).toBeInTheDocument()
    expect(document.querySelector('.review-note img')).toBeNull()
  })
})
