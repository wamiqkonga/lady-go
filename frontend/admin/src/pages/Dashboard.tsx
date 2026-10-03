const tiles = [
  'Active drivers',
  'Online drivers',
  'Active rides',
  'Pending bookings',
  'Completed rides (today)',
  'Cancelled rides (today)',
  'Revenue (today)',
  'Customers',
  'Driver verification requests',
  'Open support / safety issues',
  'System alerts',
]

export default function Dashboard() {
  return (
    <section>
      <h1>Dashboard</h1>
      <p className="muted">Live figures are connected in Phase 9.</p>
      <div className="tiles">
        {tiles.map((t) => (
          <div className="tile" key={t}>
            <div className="tile-label">{t}</div>
            <div className="tile-value" aria-label={`${t}: no data yet`}>
              —
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
