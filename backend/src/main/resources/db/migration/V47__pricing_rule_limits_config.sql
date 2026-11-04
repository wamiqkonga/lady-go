-- V47: absolute limits and the large-change threshold for the admin Pricing > Fares screen
-- (docs/development/plans/2026-11-03-admin-pricing-rules.md section 4b). Typing-slip protection enforced on the
-- server: a fare field above its limit is refused, and a change of more than the threshold percent in any field needs
-- an explicit confirmation. Money limits are whole paise (ADR-007). Additive; rollback = delete these rows (the code
-- falls back to the same defaults). No fare is seeded here: the owner enters the first fare through the portal.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('pricing.limit.base_fare.max.paise',        '50000',  'INTEGER', 'GLOBAL',
        'Fares: highest base fare the portal accepts, in paise (Rs 500)'),
    ('pricing.limit.per_km.max.paise',           '10000',  'INTEGER', 'GLOBAL',
        'Fares: highest per-kilometre rate the portal accepts, in paise (Rs 100)'),
    ('pricing.limit.per_minute.max.paise',       '2000',   'INTEGER', 'GLOBAL',
        'Fares: highest per-minute rate the portal accepts, in paise (Rs 20)'),
    ('pricing.limit.minimum_fare.max.paise',     '100000', 'INTEGER', 'GLOBAL',
        'Fares: highest minimum fare the portal accepts, in paise (Rs 1,000)'),
    ('pricing.limit.cancellation_fee.max.paise', '50000',  'INTEGER', 'GLOBAL',
        'Fares: highest cancellation fee the portal accepts, in paise (Rs 500)'),
    ('pricing.limit.service_fee_flat.max.paise', '10000',  'INTEGER', 'GLOBAL',
        'Fares: highest flat service fee the portal accepts, in paise (Rs 100)'),
    ('pricing.limit.service_fee_pct.max',        '30',     'DECIMAL', 'GLOBAL',
        'Fares: highest percentage service fee the portal accepts (0-100)'),
    ('pricing.change.confirm.pct',               '50',     'INTEGER', 'GLOBAL',
        'Fares: a change of more than this percent in any field needs "I confirm this large change" (1-500)');
