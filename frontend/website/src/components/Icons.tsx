import type { ReactNode } from 'react'

/** One stroke icon family (24px grid, 1.8 stroke, round caps) used across the site. Decorative by default:
 *  pass `label` only when the icon stands alone and carries meaning. */
const paths = {
  arrow: <path d="M5 12h14M13 6l6 6-6 6" />,
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  wallet: (
    <>
      <path d="M4 7.5A2.5 2.5 0 016.5 5H18a1 1 0 011 1v2" />
      <path d="M4 7.5V17a2 2 0 002 2h13a1 1 0 001-1v-9a1 1 0 00-1-1H6.5A2.5 2.5 0 014 7.5z" />
      <circle cx="16" cy="13.5" r="1.2" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3l7 3v5.5c0 4.4-2.9 7.9-7 9.5-4.1-1.6-7-5.1-7-9.5V6l7-3z" />
      <path d="M9 12l2.2 2.2L15.5 10" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8.5" r="3.2" />
      <path d="M3 19c.4-3 2.7-5 6-5s5.6 2 6 5" />
      <path d="M16 5.6a3 3 0 010 5.8M18 14.3c1.7.6 2.8 2.2 3 4.7" />
    </>
  ),
  pin: (
    <>
      <path d="M12 21s7-5.7 7-11a7 7 0 10-14 0c0 5.3 7 11 7 11z" />
      <circle cx="12" cy="10" r="2.5" />
    </>
  ),
  phone: (
    <>
      <rect x="7" y="2.8" width="10" height="18.4" rx="2.4" />
      <path d="M11 18h2" />
    </>
  ),
  id: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <circle cx="9" cy="11" r="2" />
      <path d="M6 16c.4-1.4 1.6-2.2 3-2.2s2.6.8 3 2.2M14.5 10h3.5M14.5 13.5H18" />
    </>
  ),
  car: (
    <>
      <path d="M5 16.5V13l1.7-4.6A2 2 0 018.6 7h6.8a2 2 0 011.9 1.4L19 13v3.5" />
      <path d="M4 13h16v3.5a1 1 0 01-1 1H5a1 1 0 01-1-1V13z" />
      <circle cx="7.8" cy="15" r=".6" />
      <circle cx="16.2" cy="15" r=".6" />
      <path d="M6.5 17.5V19M17.5 17.5V19" />
    </>
  ),
  file: (
    <>
      <path d="M7 3h7l5 5v11a2 2 0 01-2 2H7a2 2 0 01-2-2V5a2 2 0 012-2z" />
      <path d="M14 3v5h5M9 13h6M9 16.5h4" />
    </>
  ),
  camera: (
    <>
      <path d="M4 8.5A1.5 1.5 0 015.5 7H8l1.2-2h5.6L16 7h2.5A1.5 1.5 0 0120 8.5v9a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 014 17.5v-9z" />
      <circle cx="12" cy="13" r="3.2" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10.5" width="14" height="10" rx="2.4" />
      <path d="M8 10.5V8a4 4 0 018 0v2.5M12 15v2" />
    </>
  ),
  heart: <path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0112 7.4 4.3 4.3 0 0119.5 10c0 5.4-7.5 10-7.5 10z" />,
  share: (
    <>
      <circle cx="6" cy="12" r="2.4" />
      <circle cx="17.5" cy="6" r="2.4" />
      <circle cx="17.5" cy="18" r="2.4" />
      <path d="M8.2 10.8l7.1-3.6M8.2 13.2l7.1 3.6" />
    </>
  ),
  alert: (
    <>
      <path d="M12 3.5l9 15.5H3l9-15.5z" />
      <path d="M12 10v4M12 17h.01" />
    </>
  ),
  star: <path d="M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7-5.1-2.7-5.1 2.7 1-5.7-4.1-4 5.7-.8L12 3.6z" />,
  support: (
    <>
      <path d="M4.5 13v-1a7.5 7.5 0 0115 0v1" />
      <rect x="3.5" y="12.5" width="3.8" height="6" rx="1.6" />
      <rect x="16.7" y="12.5" width="3.8" height="6" rx="1.6" />
      <path d="M18.6 18.5c0 1.6-1.6 2.5-4 2.5" />
    </>
  ),
  mail: (
    <>
      <rect x="3" y="5.5" width="18" height="13" rx="2.5" />
      <path d="M4 7.5l8 6 8-6" />
    </>
  ),
  route: (
    <>
      <circle cx="6.5" cy="6.5" r="2.5" />
      <circle cx="17.5" cy="17.5" r="2.5" />
      <path d="M9 6.5h5.5a3 3 0 010 6h-5a3 3 0 000 6H15" />
    </>
  ),
} satisfies Record<string, ReactNode>

export type IconName = keyof typeof paths

type Props = { name: IconName; size?: number; label?: string; className?: string }

export default function Icon({ name, size = 24, label, className }: Props) {
  return (
    <svg
      className={className ? `icon ${className}` : 'icon'}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      {paths[name]}
    </svg>
  )
}
