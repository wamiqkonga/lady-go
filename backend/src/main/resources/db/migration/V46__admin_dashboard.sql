-- V46: admin dashboard (GET /api/v1/admin/dashboard). The two service targets, so they can be tuned without a deploy
-- (bounded in AdminConfigService). The review target is the verification queue's own overdue limit, which the code
-- already read with a default of 24 but never had a row for; the dashboard and the queue share it. Plus the indexes the dashboard's count queries use so
-- they stay cheap as the tables grow. Additive; rollback = delete the two configuration rows and drop the indexes.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('verification.queue.overdue.hours', '24', 'INTEGER', 'GLOBAL',
        'A captain application waiting longer than this many hours for review is overdue on the verification queue and the dashboard (1-168)'),
    ('admin.dashboard.sos_ack_target.minutes', '5', 'INTEGER', 'GLOBAL',
        'Dashboard: an SOS nobody has acknowledged after this many minutes is shown as over target (1-60)');

CREATE INDEX idx_rides_completed_at ON rides (completed_at) WHERE status = 'COMPLETED';
CREATE INDEX idx_rides_completed_customer ON rides (customer_id) WHERE status = 'COMPLETED';
CREATE INDEX idx_booking_events_to_status_created ON booking_events (to_status, created_at);
CREATE INDEX idx_customers_created_at ON customers (created_at);
CREATE INDEX idx_safety_incidents_type_created ON safety_incidents (type, created_at);
