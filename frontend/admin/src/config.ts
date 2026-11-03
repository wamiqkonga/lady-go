/** Runtime configuration. Never hard-code API URLs in components. */
export const API_BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080'

/** Live map tiles. Defaults to the public OpenStreetMap server (fine for the pilot); a paid provider
 *  replaces it by setting both variables at build time. The tile origin must also be in the CSP
 *  `img-src` - vite.config.ts derives it from this same variable. */
export const MAP_TILE_URL: string =
  (import.meta.env.VITE_MAP_TILE_URL as string | undefined) ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
export const MAP_ATTRIBUTION: string =
  (import.meta.env.VITE_MAP_ATTRIBUTION as string | undefined) ??
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors'
