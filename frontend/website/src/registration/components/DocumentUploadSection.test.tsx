import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentUploadSection from './DocumentUploadSection'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function jpegFile(name = 'licence.jpg', sizeBytes = 1024) {
  return new File([new Uint8Array(sizeBytes)], name, { type: 'image/jpeg' })
}

describe('DocumentUploadSection', () => {
  it('rejects a non-image file client-side without ever calling the server', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<DocumentUploadSection type="LICENSE" label="Driving licence" onUploaded={vi.fn()} />)

    // fireEvent, not userEvent.upload - userEvent respects the input's accept filter and silently
    // declines to apply a non-matching file, same as an OS file picker would; a real client-side
    // defence also has to handle drag-and-drop, which ignores accept entirely.
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const pdf = new File(['x'], 'licence.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [pdf] } })

    expect(screen.getByRole('alert')).toHaveTextContent('Only JPEG or PNG images are accepted.')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects an oversized file client-side without ever calling the server', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<DocumentUploadSection type="LICENSE" label="Driving licence" onUploaded={vi.fn()} />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const tooBig = jpegFile('licence.jpg', 9 * 1024 * 1024)
    await userEvent.upload(input, tooBig)

    expect(screen.getByRole('alert')).toHaveTextContent('too large')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a preview and uploads a valid file with number and expiry', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ success: true, data: { id: 'doc-1', type: 'LICENSE', status: 'PENDING', documentNumber: 'DL-1', expiresAt: '2030-01-01' }, error: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const onUploaded = vi.fn()

    render(<DocumentUploadSection type="LICENSE" label="Driving licence" needsNumber needsExpiry onUploaded={onUploaded} />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    await userEvent.upload(input, jpegFile())
    await waitFor(() => expect(screen.getByAltText('Preview')).toBeInTheDocument())

    await userEvent.type(screen.getByPlaceholderText('Document number'), 'DL-1')
    const dateInput = screen.getByLabelText('Expiry date')
    await userEvent.type(dateInput, '2030-01-01')

    await userEvent.click(screen.getByRole('button', { name: 'Upload' }))

    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toContain('type=LICENSE')
    expect(url).toContain('number=DL-1')
    expect(url).toContain('expiresAt=2030-01-01')
  })

  it('shows a rejection note as plain text and allows resubmission', async () => {
    render(
      <DocumentUploadSection
        type="INSURANCE"
        label="Insurance"
        existing={{
          id: 'doc-1', type: 'INSURANCE', status: 'REJECTED',
          reviewNote: '<img src=x onerror=alert(1)>blurry photo',
        }}
        onUploaded={vi.fn()}
      />,
    )

    expect(screen.getByText(/blurry photo/)).toBeInTheDocument()
    expect(document.querySelector('.review-note img')).toBeNull()
    expect(document.querySelector('input[type="file"]')).toBeInTheDocument() // resubmission is allowed
  })

  it('hides the upload control once a document is approved', () => {
    render(
      <DocumentUploadSection
        type="INSURANCE"
        label="Insurance"
        existing={{ id: 'doc-1', type: 'INSURANCE', status: 'APPROVED' }}
        onUploaded={vi.fn()}
      />,
    )

    expect(screen.getByText('Approved')).toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).toBeNull()
  })
})
