import { useState } from 'react'
import { currentTheme, setTheme, type Theme } from '../theme'

export default function ThemeToggle() {
  const [theme, setLocal] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-label={`Switch to ${next} mode`}
      onClick={() => {
        setTheme(next)
        setLocal(next)
      }}
    >
      {theme === 'dark' ? 'Light mode' : 'Dark mode'}
    </button>
  )
}
