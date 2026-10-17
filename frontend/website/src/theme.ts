export type Theme = 'light' | 'dark'

const KEY = 'ladygo-theme'

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null
  }
}

export function currentTheme(): Theme {
  const explicit = document.documentElement.dataset.theme
  if (explicit === 'light' || explicit === 'dark') return explicit
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** Applies a remembered choice before first render; with none, the OS setting rules via CSS. */
export function applyStoredTheme(): void {
  const t = stored()
  if (t) document.documentElement.dataset.theme = t
}

export function setTheme(t: Theme): void {
  document.documentElement.dataset.theme = t
  try {
    localStorage.setItem(KEY, t)
  } catch {
    // private mode / storage blocked: the choice just lasts for this page view
  }
}
