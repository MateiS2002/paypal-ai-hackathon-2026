-- S8: the policy engine's verdict on every stored quote, and the suppliers whose answer to an RFQ was refused.
-- Additive: existing quotes read as OK until the counter round re-checks them.
alter table quote
    add column policy_status  text not null default 'OK' check (policy_status in ('OK', 'NEEDS_APPROVAL', 'BLOCKED')),
    -- A JSON array of the engine's own sentences.
    add column policy_reasons text not null default '[]';

-- A reply the café read as an envelope but refused as a quote (unknown field, broken rule). Shown on the need so the
-- owner sees "message refused" instead of a silent gap; the refused message itself is in conversation_message.
create table need_refusal (
    need_id         uuid        not null references need (id),
    supplier_domain text        not null,
    reason          text        not null,
    created_at      timestamptz not null default now(),
    primary key (need_id, supplier_domain)
);
