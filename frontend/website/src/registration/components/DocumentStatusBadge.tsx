/** Review status of one uploaded document, shared by the document and selfie steps. */
export default function DocumentStatusBadge({ status }: { status?: string }) {
  if (status === 'APPROVED') return <span className="badge badge-ok">Approved</span>
  if (status === 'REJECTED') return <span className="badge badge-bad">Rejected</span>
  return <span className="badge badge-warn">Pending review</span>
}
