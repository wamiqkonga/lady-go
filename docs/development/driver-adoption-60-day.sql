-- Captain adoption counts for the first 60 days (plan: docs/development/plans/2026-11-02-driver-registration-amendments.md,
-- section 1). READ ONLY: run against a reporting connection, never edits data.
--
-- TODO(owner): the 60-day target number is not decided (plan question Q1). Put it in :target below, for example
-- "captains fully registered and approved by day 60". Until then the last column reads NULL.
--
-- Usage (psql): psql "$REPORTING_URL" -v launch="'2026-11-09'" -v target=NULL -f docs/development/driver-adoption-60-day.sql
-- :launch is the day the pilot opened to captains (a date in quotes); :target is the number, or NULL.

WITH cohort AS (
    SELECT u.id, u.created_at, u.password_set_at, u.last_login_at
    FROM users u
    WHERE u.role = 'DRIVER'
      AND u.created_at >= :launch::date
      AND u.created_at <  :launch::date + interval '60 days'
),
finished AS (
    -- every required step is in: a vehicle, an RC photo, a selfie and the accepted declaration
    SELECT d.user_id
    FROM drivers d
    JOIN cohort c ON c.id = d.user_id
    WHERE EXISTS (SELECT 1 FROM vehicles v WHERE v.driver_id = d.id)
      AND EXISTS (SELECT 1 FROM driver_documents x WHERE x.driver_id = d.id AND x.type = 'VEHICLE_REGISTRATION')
      AND EXISTS (SELECT 1 FROM driver_documents x WHERE x.driver_id = d.id AND x.type = 'SELFIE')
      AND EXISTS (SELECT 1 FROM self_declarations s WHERE s.subject_type = 'DRIVER' AND s.subject_id = d.id)
),
approved AS (
    SELECT d.user_id FROM drivers d JOIN cohort c ON c.id = d.user_id WHERE d.verification_status = 'APPROVED'
)
SELECT
    (SELECT count(*) FROM cohort)                                           AS verified_code_signed_up,
    (SELECT count(*) FROM cohort WHERE password_set_at IS NOT NULL)         AS set_a_password,
    (SELECT count(*) FROM finished)                                         AS finished_all_steps,
    (SELECT count(*) FROM approved)                                         AS approved,
    -- came back by password within 7 days of registering: a later sign-in than the day they signed up, after a password
    (SELECT count(*) FROM cohort
      WHERE password_set_at IS NOT NULL AND last_login_at > created_at + interval '1 day'
        AND last_login_at <= created_at + interval '7 days')                AS returned_within_7_days,
    :target::int                                                            AS target,
    CASE WHEN :target::int IS NULL THEN NULL
         ELSE round(100.0 * (SELECT count(*) FROM approved) / :target::int, 1) END AS percent_of_target;
