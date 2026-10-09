-- V22: trip sharing (docs/api/safety.md section 4) - config only, no schema change. The V10
-- trip_shares table already has everything this needs (ride_id, created_by, token_hash, expires_at,
-- revoked_at).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('trip_share.max_active_per_ride', '3', 'INTEGER', 'GLOBAL',
        'Max simultaneously active (not revoked, not expired) trip-share links per ride'),
    ('trip_share.default_ttl.hours', '12', 'INTEGER', 'GLOBAL',
        'Safety-ceiling expiry from creation time, independent of when the ride actually ends'),
    ('trip_share.post_ride_grace.minutes', '30', 'INTEGER', 'GLOBAL',
        'Once the ride is COMPLETED/CANCELLED, a share stays valid for this long past that, then is treated as expired regardless of its stored expires_at'),
    ('trip_share.lookup.rate.max.attempts', '10', 'INTEGER', 'GLOBAL',
        'Max public-lookup attempts per token per window'),
    ('trip_share.lookup.rate.window.seconds', '60', 'INTEGER', 'GLOBAL',
        'Window for the per-token lookup limit'),
    ('trip_share.lookup.ip.rate.max.attempts', '30', 'INTEGER', 'GLOBAL',
        'Max public-lookup attempts per client IP per window - wider than the per-token limit, catches token-guessing across many different tokens from one source'),
    ('trip_share.lookup.ip.rate.window.seconds', '60', 'INTEGER', 'GLOBAL',
        'Window for the per-IP lookup limit');
