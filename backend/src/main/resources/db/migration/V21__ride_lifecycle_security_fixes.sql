-- V21: fresh-context adversarial review of Phase 6.5 (docs/api/ride-lifecycle.md) found two real
-- issues beyond the automated-scan finding already fixed in the prior migration/commit:
--
-- CRITICAL: endRideEarly's fare recompute floors to the pricing rule's bare minimum fare when
-- actualDistanceM/actualDurationS are near zero - trivially reachable by ending a ride the instant
-- it starts, paying far less than the agreed estimate for zero cost to either party. Fixed in code
-- (RideLifecycleService) with a minimum elapsed-time/distance threshold below which the full
-- estimated fare is charged instead of the distance-based recompute; these are its config keys.
--
-- HIGH: disputeCash never wrote to the bookings row at all (only inserted a RideFlag), so it had no
-- @Version to race against CashConfirmationSweepJob's concurrent read-then-complete - a dispute
-- landing in the same window as a sweep tick could lose silently (booking completes anyway, flag
-- exists but nobody's blocking on it). cash_disputed_at gives disputeCash an actual row write to bump
-- the version against, and the sweep's own per-booking transition now goes through the same
-- optimistic-lock machinery every other booking transition already relies on.

ALTER TABLE bookings ADD COLUMN cash_disputed_at timestamptz;

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('ride.early_end.min_elapsed.seconds', '120', 'INTEGER', 'GLOBAL',
        'Below this elapsed time since RIDE_STARTED, an early end charges the full estimated fare rather than a distance-based recompute'),
    ('ride.early_end.min_distance.m', '500', 'INTEGER', 'GLOBAL',
        'Below this actual distance travelled, an early end charges the full estimated fare rather than a distance-based recompute');
