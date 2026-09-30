CREATE TABLE app_user
(
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    token      TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE account
(
    id         UUID PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES app_user (id),
    currency   TEXT           NOT NULL,
    balance    NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status     TEXT           NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_user ON account (user_id);

CREATE TABLE account_transaction
(
    id                      UUID PRIMARY KEY,
    account_id              UUID           NOT NULL REFERENCES account (id),
    type                    TEXT           NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                TEXT           NOT NULL,
    status                  TEXT           NOT NULL,
    original_transaction_id UUID REFERENCES account_transaction (id),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- REFUND must reference an original; no other type may
    CONSTRAINT chk_refund_reference CHECK ((type = 'REFUND') = (original_transaction_id IS NOT NULL))
);

CREATE INDEX idx_transaction_account ON account_transaction (account_id, created_at DESC);
CREATE INDEX idx_transaction_original ON account_transaction (original_transaction_id)
    WHERE original_transaction_id IS NOT NULL;

CREATE TABLE idempotency_record
(
    user_id         UUID        NOT NULL REFERENCES app_user (id),
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    in_flight       BOOLEAN     NOT NULL,
    transaction_id  UUID REFERENCES account_transaction (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);
