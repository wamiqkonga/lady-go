import Page from '../components/Page'

const faqs = [
  { q: 'When will Lady Go launch?', a: 'The service is in development. We will announce launch details on this site.' },
  { q: 'Who can ride with Lady Go?', a: 'Eligibility rules will be published before launch.' },
  { q: 'How are drivers verified?', a: 'Drivers submit identity, licence and vehicle documents that our team reviews before they can accept rides.' },
  { q: 'How do I pay?', a: 'Payment options will be confirmed before launch.' },
]

export default function Faq() {
  return (
    <Page title="Frequently asked questions">
      {faqs.map((f) => (
        <details key={f.q}>
          <summary>{f.q}</summary>
          <p>{f.a}</p>
        </details>
      ))}
    </Page>
  )
}
