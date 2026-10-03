import type { ReactNode } from 'react'

type Props = { title: string; lead?: string; children?: ReactNode }

export default function Page({ title, lead, children }: Props) {
  return (
    <article className="page">
      <h1>{title}</h1>
      {lead ? <p className="lead">{lead}</p> : null}
      {children}
    </article>
  )
}
