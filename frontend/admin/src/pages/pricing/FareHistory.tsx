import type { components } from '../../lib/api-types'
import { formatRupees } from './money'

type Rule = components['schemas']['PricingRuleResponse']

function when(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString() : 'open-ended'
}

export default function FareHistory({ rules }: { rules: Rule[] }) {
  if (rules.length === 0) return <p className="muted">No fare history yet.</p>
  return (
    <div className="table-scroll">
      <table className="list-table fare-history" aria-label="Fare history">
        <thead>
          <tr>
            <th>From</th>
            <th>Until</th>
            <th>Status</th>
            <th>Base fare</th>
            <th>Per km</th>
            <th>Per minute</th>
            <th>Minimum</th>
            <th>Cancellation</th>
            <th>Service fee</th>
          </tr>
        </thead>
        <tbody>
          {rules.map((r) => (
            <tr key={r.id}>
              <td data-label="From">{when(r.effectiveFrom)}</td>
              <td data-label="Until">{when(r.effectiveTo)}</td>
              <td data-label="Status">{r.status === 'IN_FORCE' ? 'In force' : r.status === 'SCHEDULED' ? 'Scheduled' : 'Ended'}</td>
              <td data-label="Base fare">{formatRupees(r.baseFare)}</td>
              <td data-label="Per km">{formatRupees(r.perKm)}</td>
              <td data-label="Per minute">{formatRupees(r.perMinute)}</td>
              <td data-label="Minimum">{formatRupees(r.minimumFare)}</td>
              <td data-label="Cancellation">{formatRupees(r.cancellationFee)}</td>
              <td data-label="Service fee">
                {r.serviceFeePct}% + {formatRupees(r.serviceFeeFlat)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
