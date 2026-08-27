CREATE TABLE reservation (
    id UUID PRIMARY KEY,
    sku TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);

CREATE TABLE idempotency_record (
    idempotency_key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    response_body TEXT
);
