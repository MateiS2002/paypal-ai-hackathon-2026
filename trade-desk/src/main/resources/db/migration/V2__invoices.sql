-- S1: invoices sent through PayPal Invoicing v2. Money is stored in minor units (cents), never as a float.

create table customer (
    id         uuid primary key,
    name       text        not null,
    -- Stored lower-case, so finding a customer by email ignores case.
    email      text        not null unique,
    created_at timestamptz not null default now()
);

create table invoice (
    id                uuid primary key,
    paypal_invoice_id text unique,
    invoice_number    text,
    customer_id       uuid        not null references customer (id),
    customer_name     text        not null,
    customer_email    text        not null,
    total_minor       bigint      not null check (total_minor > 0),
    currency          char(3)     not null,
    status            text        not null,
    payer_view_url    text,
    due_date          date,
    -- NEGOTIATION is set by S3, MESSY_ORDER and source_text by S7; both exist now so they need no migration.
    source            text        not null check (source in ('MANUAL', 'NEGOTIATION', 'MESSY_ORDER')),
    source_text       text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    paid_at           timestamptz
);

create index invoice_status_idx on invoice (status);

create table invoice_line (
    invoice_id       uuid          not null references invoice (id),
    line_no          int           not null,
    description      text          not null,
    quantity         numeric(10, 3) not null check (quantity > 0),
    unit_price_minor bigint        not null check (unit_price_minor > 0),
    line_total_minor bigint        not null check (line_total_minor > 0),
    primary key (invoice_id, line_no)
);

-- One row per Idempotency-Key, written before any PayPal call, so a retry resumes instead of creating a second invoice.
create table invoice_operation (
    idempotency_key   uuid primary key,
    request_hash      text        not null,
    paypal_request_id uuid        not null,
    step              text        not null check (step in ('CREATING', 'CREATED', 'SENT')),
    invoice_id        uuid        not null references invoice (id),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
