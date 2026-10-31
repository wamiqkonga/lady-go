import type { ReactNode } from 'react'
import Icon, { type IconName } from './Icons'
import { PageArt } from './Illustrations'

type Props = { title: string; lead?: string; icon?: IconName; children?: ReactNode }

/** Shell for the text pages (About, Safety, FAQ, legal…): a header band with art and a readable column. */
export default function Page({ title, lead, icon = 'heart', children }: Props) {
  return (
    <article className="page">
      <header className="page-head">
        <div className="container page-head-row">
          <div>
            <h1>{title}</h1>
            {lead ? <p className="lead">{lead}</p> : null}
          </div>
          <PageArt>
            <Icon name={icon} size={64} />
          </PageArt>
        </div>
      </header>
      <div className="container">
        <div className="prose">{children}</div>
      </div>
    </article>
  )
}
