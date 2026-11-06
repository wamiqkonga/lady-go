import { useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import { NAME_MAX, cleanFullName, fullNameError } from '../lib/fullName'
import type { components } from '../lib/api-types'

type DriverProfileResponse = components['schemas']['DriverProfileResponse']

/** First step after choosing the city: the captain's full name, so reviewers can match the documents to a person. */
export default function NameStep({ initial, onSaved }: { initial: string | null; onSaved: (profile: DriverProfileResponse) => void }) {
  const [value, setValue] = useState(initial ?? '')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const validation = fullNameError(value)
  const shownError = (touched && validation) || serverError
  const unchanged = !!initial && cleanFullName(value) === cleanFullName(initial)

  async function submit() {
    setTouched(true)
    setServerError(null)
    if (validation) return
    setBusy(true)
    try {
      const result = await client.PUT('/api/v1/drivers/me/name', { body: { fullName: cleanFullName(value) } })
      const profile = unwrap(result)
      setValue(profile.fullName ?? cleanFullName(value))
      setSaved(true)
      onSaved(profile)
    } catch (err) {
      setServerError(err instanceof ApiCallError ? err.message : 'Could not save your name. Check your connection and try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className="section form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <h2>Your name</h2>
      <label htmlFor="full-name">Full name</label>
      <p id="full-name-hint" className="muted">
        As it appears on your driving licence or RC. Reviewers use this to match your documents.
      </p>
      <input
        id="full-name"
        type="text"
        autoComplete="name"
        autoCapitalize="words"
        maxLength={NAME_MAX * 2}
        value={value}
        aria-describedby={shownError ? 'full-name-hint full-name-error' : 'full-name-hint'}
        aria-invalid={shownError ? true : undefined}
        onChange={(e) => {
          setValue(e.target.value)
          setSaved(false)
          setServerError(null)
        }}
        onBlur={() => setTouched(true)}
      />
      {shownError && <p id="full-name-error" role="alert" className="error">{shownError}</p>}
      {saved && !shownError && <p role="status" className="muted">Name saved.</p>}
      <button type="submit" disabled={busy || unchanged} aria-busy={busy}>
        {busy ? (<><span className="spinner" aria-hidden="true" /> Saving…</>) : 'Save name'}
      </button>
    </form>
  )
}
