-- V19: location staleness is judged from the SERVER's own receive time, not the device's clock (a
-- device with a wrong/skewed clock must not have every update rejected as "stale" just because its
-- own clock disagrees with real time). The device-supplied timestamp is kept, but only to order
-- consecutive updates relative to EACH OTHER for the teleport-speed check - so it needs its own
-- column, separate from drivers.current_location_at, which now means "server receive time of the
-- last accepted update".

ALTER TABLE drivers ADD COLUMN current_location_device_recorded_at timestamptz;

-- The future-skew check is gone (there is no "device clock vs server clock" comparison left to
-- bound); max_age now bounds the gap between consecutive updates' SERVER receive times (if a driver
-- hasn't been heard from in longer than this, by our own clock, the next update is treated as a
-- fresh start rather than compared for speed against a now-meaningless baseline).
DELETE FROM configuration WHERE key = 'driver.location.max_future_skew.seconds';
UPDATE configuration SET value = '60' WHERE key = 'driver.location.max_age.seconds';
