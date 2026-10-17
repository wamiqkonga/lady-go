/** A `data:` URI, not `URL.createObjectURL`'s `blob:` one - `data:` is already an allowed img-src
 *  scheme under the strict production CSP (same policy frontend/admin uses), so this avoids adding
 *  `blob:` as a CSP exception just for a local preview that never needs to be a network-fetchable
 *  resource in the first place. */
export function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read the file.'))
    reader.readAsDataURL(file)
  })
}
