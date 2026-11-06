import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * A table cell whose whole area is a link to the row's detail page, so clicking anywhere on the row works.
 * Only the first cell is a keyboard tab stop and a screen-reader link; the others are the same link for the mouse.
 */
export function LinkCell({ to, first = false, label, children }: { to: string; first?: boolean; label?: string; children: ReactNode }) {
  return (
    <td className="row-link-cell" data-first={first ? '' : undefined}>
      <Link to={to} className="row-link" data-label={label} tabIndex={first ? undefined : -1} aria-hidden={first ? undefined : true}>
        {children}
      </Link>
    </td>
  )
}
