-- Required note on an admin's ride-flag review (docs/product/admin-web-design.md section 4 update):
-- the existing admin_reviewed_by/admin_reviewed_at (V24) record WHO and WHEN, but not WHY - a required
-- note means a later dispute over a triage decision has something to point to beyond "someone looked
-- at it". Nullable at the column level only because older rows (reviewed before this migration, if
-- any) have none; the application layer requires it on every new review.
ALTER TABLE ride_flags ADD COLUMN admin_review_note text;
