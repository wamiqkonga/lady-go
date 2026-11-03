/** Runtime configuration. Never hard-code API URLs in components. */
export const API_BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080'

/** Public support mailbox, shown on the Contact page and in the captain registration dialog. */
export const SUPPORT_EMAIL = 'supportladygo@gmail.com'
