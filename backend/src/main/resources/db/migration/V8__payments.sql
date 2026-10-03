-- V8: payments. Provider-agnostic (provider is a string); amounts in minor units.
-- Card data is never stored: payment_methods holds provider tokens only.

CREATE TABLE payment_methods (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id        uuid        NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    provider           varchar(50) NOT NULL,
    provider_token     varchar(255) NOT NULL,
    type               varchar(20) NOT NULL CHECK (type IN ('CARD', 'UPI', 'WALLET', 'OTHER')),
    display_label      varchar(50),           -- e.g. "Visa ending 4242"
    is_default         boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_methods_token UNIQUE (provider, provider_token)
);
CREATE INDEX idx_payment_methods_customer ON payment_methods (customer_id);
CREATE UNIQUE INDEX uq_payment_methods_one_default ON payment_methods (customer_id) WHERE is_default;

CREATE TABLE payment_transactions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         uuid        NOT NULL REFERENCES bookings (id),
    customer_id        uuid        NOT NULL REFERENCES customers (id),
    provider           varchar(50) NOT NULL,
    provider_reference varchar(100),
    method             varchar(10) NOT NULL CHECK (method IN ('CASH', 'ONLINE')),
    amount             bigint      NOT NULL CHECK (amount >= 0),
    currency           char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status             varchar(25) NOT NULL DEFAULT 'INITIATED' CHECK (status IN (
        'INITIATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
    idempotency_key    varchar(100) NOT NULL,
    failure_code       varchar(100),
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_transactions_idempotency UNIQUE (idempotency_key)
);
CREATE UNIQUE INDEX uq_payment_transactions_provider_ref
    ON payment_transactions (provider, provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX idx_payment_transactions_booking ON payment_transactions (booking_id);
CREATE INDEX idx_payment_transactions_customer ON payment_transactions (customer_id, created_at DESC);
CREATE TRIGGER trg_payment_transactions_updated_at BEFORE UPDATE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Webhook deduplication: (provider, event_id) is unique so a replayed event is processed once.
-- Payloads are stored sanitised (no card data, no secrets).
CREATE TABLE payment_webhook_events (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider     varchar(50)  NOT NULL,
    event_id     varchar(150) NOT NULL,
    payload      jsonb        NOT NULL,
    status       varchar(15)  NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'IGNORED')),
    received_at  timestamptz  NOT NULL DEFAULT now(),
    processed_at timestamptz,
    CONSTRAINT uq_payment_webhook_events UNIQUE (provider, event_id)
);

CREATE TABLE refunds (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id     uuid        NOT NULL REFERENCES payment_transactions (id),
    amount             bigint      NOT NULL CHECK (amount > 0),
    reason             text,
    status             varchar(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    provider_reference varchar(100),
    created_by         uuid REFERENCES users (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_transaction ON refunds (transaction_id);
CREATE TRIGGER trg_refunds_updated_at BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
