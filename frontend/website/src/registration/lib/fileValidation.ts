/**
 * Client-side pre-check only - a UX improvement (fail before a slow upload, not instead of the
 * server check). The backend's UploadValidator is the actual authority: same content types, and the
 * same default max size (verification.upload.max_size.bytes, 8 MiB) mirrored here as a constant - if
 * an admin changes that config value, this client-side number simply becomes slightly conservative
 * or slightly generous; the server still enforces whatever the real value is.
 */
export const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png']
export const MAX_FILE_SIZE_BYTES = 8 * 1024 * 1024

export function validateImageFile(file: File): string | null {
  if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
    return 'Only JPEG or PNG images are accepted.'
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    return `File is too large (max ${Math.round(MAX_FILE_SIZE_BYTES / 1024 / 1024)} MB).`
  }
  return null
}
