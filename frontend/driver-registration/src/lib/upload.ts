import { API_BASE_URL } from '../config'
import { getAccessToken } from './auth'
import { ApiCallError } from './client'
import type { components } from './api-types'

type DocumentType = NonNullable<components['schemas']['DriverDocumentResponse']['type']>
type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']

/**
 * Raw fetch, not the generated `client` - springdoc represents a multipart endpoint's body as a
 * single-field `application/json` schema ({@code file: string} typed "binary"), which doesn't match
 * what actually has to be sent (a real `multipart/form-data` body); openapi-fetch has no multipart
 * helper for that shape. The query params (type/number/expiresAt) and response envelope are still
 * exactly what the OpenAPI spec says - only the body transport bypasses the generated client.
 */
export async function uploadDocument(
  file: File, type: DocumentType, options?: { number?: string; expiresAt?: string },
): Promise<DriverDocumentResponse> {
  const params = new URLSearchParams({ type })
  if (options?.number) params.set('number', options.number)
  if (options?.expiresAt) params.set('expiresAt', options.expiresAt)

  const body = new FormData()
  body.append('file', file)

  return doUpload(`/api/v1/drivers/me/documents?${params.toString()}`, body)
}

export async function uploadSelfie(file: File): Promise<DriverDocumentResponse> {
  const body = new FormData()
  body.append('file', file)
  return doUpload('/api/v1/drivers/me/selfie', body)
}

async function doUpload(path: string, body: FormData): Promise<DriverDocumentResponse> {
  const token = getAccessToken()
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body,
  })
  const envelope = (await response.json().catch(() => null)) as {
    success?: boolean
    data?: DriverDocumentResponse | null
    error?: { code?: string; message?: string } | null
  } | null
  if (!response.ok || !envelope || !envelope.success || !envelope.data) {
    throw new ApiCallError(
      envelope?.error?.code ?? 'UNKNOWN_ERROR', envelope?.error?.message ?? 'Upload failed.', response.status,
    )
  }
  return envelope.data
}
