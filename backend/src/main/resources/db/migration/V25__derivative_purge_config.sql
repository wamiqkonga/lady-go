-- V25: configurable backstop age for throwaway derivative blobs (docs/product/admin-web-design.md
-- section 2 security fix - a watermarked document derivative was never recorded anywhere
-- RetentionPurgeJob looks, so it could accumulate in storage forever with no purge path).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('storage.derivative.max_age.hours', '24', 'INTEGER', 'GLOBAL',
        'Backstop: delete a throwaway watermarked derivative (driver-document-derivatives/*) this many hours after creation if the in-process scheduled delete never fired');
