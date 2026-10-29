-- S2: what a supplier sells, every agent message a desk sent or received, and the café's needs and quotes.
-- Money is stored in minor units (cents), never as a float.

-- Seeded per persona from classpath:catalogue/<persona>.json. Prices and stock are decided in Java from these rows.
create table catalogue_item (
    sku               text primary key,
    description       text           not null,
    -- Words an RFQ's product is matched against (S6 replaces this with semantic search).
    product           text           not null,
    origin            text,
    unit              text           not null check (unit in ('kg', 'lb')),
    currency          char(3)        not null,
    list_price_minor  bigint         not null check (list_price_minor > 0),
    floor_price_minor bigint         not null check (floor_price_minor > 0 and floor_price_minor <= list_price_minor),
    stock             numeric(12, 3) not null check (stock >= 0),
    lead_days         int            not null check (lead_days >= 0)
);

-- This desk's own copy of every envelope it sent or received, valid or not. raw is exactly what was on the wire, so
-- S5 can hash it; the other columns are filled when the envelope could be read.
create table conversation_message (
    id              uuid primary key,
    direction       text        not null check (direction in ('SENT', 'RECEIVED')),
    conversation_id uuid,
    message_id      uuid,
    type            text,
    from_domain     text,
    to_domain       text,
    -- The message this one answers (a quote's or reject's RFQ), so a repeated RFQ gets the stored reply.
    in_reply_to     uuid,
    raw             text        not null,
    valid           boolean     not null,
    problem         text,
    created_at      timestamptz not null default now()
);

create index conversation_message_conversation_idx on conversation_message (conversation_id);
create unique index conversation_message_reply_idx on conversation_message (in_reply_to)
    where direction = 'SENT' and in_reply_to is not null;

-- The café's needs. request is the checked purchase request as JSON; max_unit_price never leaves this desk.
create table need (
    id              uuid primary key,
    idempotency_key uuid        not null unique,
    request_hash    text        not null,
    request         text        not null,
    priority        text        not null check (priority in ('SPEED', 'PRICE', 'BALANCED')),
    status          text        not null check (status in ('COLLECTING', 'COMPLETE')),
    suppliers_asked int         not null check (suppliers_asked >= 0),
    created_at      timestamptz not null default now()
);

-- Valid quotes only; malformed ones are logged in conversation_message and never land here.
create table quote (
    id                 uuid primary key,
    need_id            uuid           not null references need (id),
    supplier_domain    text           not null,
    supplier_name      text           not null,
    simulated          boolean        not null,
    unit_price_minor   bigint         not null check (unit_price_minor > 0),
    currency           char(3)        not null,
    quantity           numeric(10, 3) not null check (quantity > 0),
    available_quantity numeric(12, 3) not null check (available_quantity >= quantity),
    unit               text           not null check (unit in ('kg', 'lb')),
    delivery_date      date           not null,
    valid_until        timestamptz    not null,
    note               text           not null,
    raw_message        text           not null,
    received_at        timestamptz    not null default now(),
    unique (need_id, supplier_domain)
);
