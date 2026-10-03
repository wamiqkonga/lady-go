type Props = { title: string; phase: string; description: string }

export default function Placeholder({ title, phase, description }: Props) {
  return (
    <section>
      <h1>{title}</h1>
      <p>{description}</p>
      {phase ? <p className="muted">Planned: {phase}.</p> : null}
    </section>
  )
}
