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

    await userEvent.type(screen.getByLabelText('Document number'), 'DL-1')
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

  describe('submitted photo preview and replace', () => {
    const pending = { id: 'doc-1', type: 'LICENSE' as const, status: 'PENDING' as const }

    function stubFetch(uploadResponse?: Response) {
      const fetchMock = vi.fn().mockImplementation(async (input: string | Request) => {
        const url = typeof input === 'string' ? input : input.url
        if (url.endsWith('/documents/doc-1/content')) {
          return new Response(new Uint8Array([0xff, 0xd8, 0xff]), { status: 200, headers: { 'Content-Type': 'image/jpeg' } })
        }
        return uploadResponse ?? jsonResponse({ success: true, data: { ...pending, id: 'doc-2' }, error: null })
      })
      vi.stubGlobal('fetch', fetchMock)
      return fetchMock
    }

    it('shows a thumbnail of the submitted photo and a Replace button while not approved', async () => {
      stubFetch()
      render(<DocumentUploadSection type="LICENSE" label="Driving licence" existing={pending} onUploaded={vi.fn()} />)

      expect(await screen.findByAltText('Driving licence you submitted')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Replace photo' })).toBeInTheDocument()
      expect(document.querySelector('input[type="file"]')).toBeNull()
    })

    it('hides Replace once the application is approved', async () => {
      stubFetch()
      render(<DocumentUploadSection type="LICENSE" label="Driving licence" existing={pending} replaceable={false} onUploaded={vi.fn()} />)

      await screen.findByAltText('Driving licence you submitted')
      expect(screen.queryByRole('button', { name: 'Replace photo' })).not.toBeInTheDocument()
    })

    it('replaces the photo: opens the picker, uploads, and reports the new pending submission', async () => {
      const fetchMock = stubFetch()
      const onUploaded = vi.fn()
      render(<DocumentUploadSection type="LICENSE" label="Driving licence" existing={pending} onUploaded={onUploaded} />)

      await userEvent.click(await screen.findByRole('button', { name: 'Replace photo' }))
      await userEvent.upload(document.querySelector('input[type="file"]') as HTMLInputElement, jpegFile())
      await userEvent.click(screen.getByRole('button', { name: 'Resubmit' }))

      await waitFor(() => expect(onUploaded).toHaveBeenCalledWith(expect.objectContaining({ id: 'doc-2', status: 'PENDING' })))
      expect(fetchMock.mock.calls.some(([u]) => String(typeof u === 'string' ? u : u.url).includes('/api/v1/drivers/me/documents?type=LICENSE'))).toBe(true)
    })

    it('shows a note instead of a broken image when the preview cannot load', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('nope', { status: 404 })))
      render(<DocumentUploadSection type="LICENSE" label="Driving licence" existing={pending} onUploaded={vi.fn()} />)

      expect(await screen.findByText(/could not show it here/)).toBeInTheDocument()
    })
  })
})
