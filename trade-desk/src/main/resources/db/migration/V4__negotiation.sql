-- S3: one counter round, the recommendation, the policy engine's limits, approvals and payables on the buyer side; the
-- supplier's own quotes and the negotiation link on its invoices. Money is stored in minor units (cents), never as a
-- float. Every desk gets every table; a café's supplier tables and a supplier's buyer tables simply stay empty.

-- Buyer: the owner's limits, one row. The policy engine (com.tradedesk.desk.policy) checks them before any accept.
create table policy (
    id                    smallint primary key default 1 check (id = 1),
    currency              char(3)     not null default 'USD',
    max_unit_price_minor  bigint      not null check (max_unit_price_minor > 0),
    approval_above_minor  bigint      not null check (approval_above_minor > 0),
    max_order_total_minor bigint      not null check (max_order_total_minor > 0),
    updated_at            timestamptz not null default now()
);

-- "Max $15/kg, ask me above $500", with a hard stop at $2,000.
insert into policy (max_unit_price_minor, approval_above_minor, max_order_total_minor) values (1500, 50000, 200000);

-- Buyer: the supplier's message ids, so a counter and an accept can name its quote, and the counter's outcome. After a
-- counter, unit_price_minor, valid_until and note hold the revised quote and previous_unit_price_minor the old price.
alter table quote
    add column quote_message_id          uuid,
    add column rfq_message_id            uuid,
    add column previous_unit_price_minor bigint check (previous_unit_price_minor > 0),
    add column counter_unit_price_minor  bigint check (counter_unit_price_minor > 0),
    add column counter_outcome           text check (counter_outcome in ('PENDING', 'MET', 'PARTLY_MET', 'HELD',
                                                                         'NO_ANSWER'));

-- Quotes stored before S3 keep their ids only in the raw envelope, which was checked when it arrived.
update quote
   set quote_message_id = (raw_message::jsonb ->> 'messageId')::uuid,
       rfq_message_id   = (raw_message::jsonb -> 'body' ->> 'rfqId')::uuid
 where raw_message is json object;

-- Buyer: one negotiation per need, from the counter round to the pick.
create table negotiation (
    need_id       uuid primary key references need (id),
    stage         text        not null check (stage in ('COUNTERING', 'READY', 'NO_OFFER')),
    pick_quote_id uuid references quote (id),
    reason        text,
    reason_source text check (reason_source in ('AI', 'TEMPLATE')),
    started_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

-- Buyer: the policy engine's decision on the pick, and the owner's if one was needed. One per need.
create table approval (
    id                uuid primary key,
    need_id           uuid        not null unique references need (id),
    quote_id          uuid        not null references quote (id),
    total_minor       bigint      not null check (total_minor > 0),
    currency          char(3)     not null,
    decision          text        not null check (decision in ('AUTO_ACCEPT', 'NEEDS_APPROVAL', 'BLOCKED')),
    -- A JSON array of the policy engine's sentences.
    reasons           text        not null,
    status            text        not null check (status in ('AUTO_APPROVED', 'PENDING', 'APPROVED', 'REJECTED',
                                                             'BLOCKED')),
    order_status      text        not null default 'NOT_SENT' check (order_status in ('NOT_SENT', 'SENDING',
                                                                                      'INVOICED', 'REFUSED',
                                                                                      'FAILED')),
    order_detail      text,
    -- Set once, before the first try, so every retry of the accept is the same message.
    accept_message_id uuid unique,
    created_at        timestamptz not null default now(),
    decided_at        timestamptz
);

-- Buyer: invoices a supplier sent this desk after an accept. status is the supplier's last report, at status_at.
create table payable (
    id                uuid primary key,
    need_id           uuid        not null references need (id),
    approval_id       uuid        not null unique references approval (id),
    supplier_domain   text        not null,
    supplier_name     text        not null,
    simulated         boolean     not null,
    description       text        not null,
    paypal_invoice_id text        not null unique,
    payer_view_url    text        not null,
    total_minor       bigint      not null check (total_minor > 0),
    currency          char(3)     not null,
    status            text        not null,
    status_at         timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index payable_created_idx on payable (created_at desc);

-- Supplier: every quote this desk sent, so a counter or an accept is checked against what was really offered.
create table supplier_quote (
    message_id         uuid primary key,
    conversation_id    uuid           not null,
    buyer_domain       text           not null,
    rfq_id             uuid           not null,
    sku                text           not null references catalogue_item (sku),
    unit_price_minor   bigint         not null check (unit_price_minor > 0),
    currency           char(3)        not null,
    quantity           numeric(10, 3) not null check (quantity > 0),
    available_quantity numeric(12, 3) not null check (available_quantity >= quantity),
    unit               text           not null check (unit in ('kg', 'lb')),
    delivery_date      date           not null,
    valid_until        timestamptz    not null,
    note               text           not null,
    -- The quote a revised quote replaces. Unique, so one quote is countered at most once.
    revises            uuid unique references supplier_quote (message_id),
    status             text           not null check (status in ('OPEN', 'SUPERSEDED', 'ACCEPTED')),
    invoice_id         uuid references invoice (id),
    created_at         timestamptz    not null default now()
);

-- Supplier: which negotiation an invoice came from, and the last status the buyer acknowledged, so the poller keeps
-- pushing invoice.status until the buyer has it.
alter table invoice
    add column conversation_id       uuid,
    add column buyer_domain          text,
    add column buyer_notified_status text;
