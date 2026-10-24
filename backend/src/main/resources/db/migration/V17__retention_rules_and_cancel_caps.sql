-- V17: corrects RetentionPurgeJob's rule (rejected submissions purged sooner than approved ones; an
-- approved+active driver's licence/selfie survive until account closure, not a fixed review-age clock)
-- and adds abuse caps on free driver-initiated eligibility cancellations. docs/api/driver-onboarding.md.

-- No account-closure feature exists yet (nothing in this codebase ever sets users.status away from
-- ACTIVE) - this column is the anchor the closure-based retention rule will use once that feature
-- ships, same "data model ships ahead of enforcement" pattern as drivers.reverification_blocks_online_at.
ALTER TABLE users ADD COLUMN status_changed_at timestamptz;

-- Flags an eligibility report created by a driver cancellation that exceeded the weekly or
-- per-driver/rider-pair free-cancellation cap, so admins can prioritise it in the review queue.
ALTER TABLE eligibility_reports ADD COLUMN cap_exceeded boolean NOT NULL DEFAULT false;

DELETE FROM configuration WHERE key = 'verification.document.retention.days';

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('verification.document.rejected_retention.days', '30', 'INTEGER', 'GLOBAL',
        'Rejected document/selfie bytes purged this many days after review'),
    ('verification.document.approved_retention.days', '90', 'INTEGER', 'GLOBAL',
        'Approved document bytes (other than LICENSE/SELFIE) purged this many days after review'),
    ('verification.driver_closure_retention.days', '90', 'INTEGER', 'GLOBAL',
        'An approved driver''s LICENSE/SELFIE bytes survive while the account is active; purged this many days after account closure'),
    ('verification.ride_selfie.retention.days', '90', 'INTEGER', 'GLOBAL',
        'Ride selfie bytes purged this many days after capture'),
    ('driver.eligibility_cancel.weekly_cap.max', '3', 'INTEGER', 'GLOBAL',
        'Penalty-free driver eligibility-cancels allowed per driver per rolling 7 days, across all riders'),
    ('driver.eligibility_cancel.pair_cap.max', '1', 'INTEGER', 'GLOBAL',
        'Penalty-free driver eligibility-cancels allowed against the same rider per rolling 7 days'),
    ('verification.document.view_url.ttl.seconds', '60', 'INTEGER', 'GLOBAL',
        'TTL for an admin-requested signed URL to view a driver document/selfie/vehicle photo');
