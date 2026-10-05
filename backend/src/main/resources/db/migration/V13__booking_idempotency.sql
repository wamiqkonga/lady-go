-- V13: idempotent booking creation (docs/api/booking.md section 2, B3).
-- Table is empty at this point in every environment this migration will ever run against
-- (Phase 4 introduces booking creation; nothing has written to bookings before it), so the
-- column can be added NOT NULL with no backfill/default needed.

ALTER TABLE bookings ADD COLUMN idempotency_key varchar(100) NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT uq_bookings_customer_idempotency UNIQUE (customer_id, idempotency_key);
