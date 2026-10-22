-- walletd core schema: double-entry ledger.
-- Money is stored as bigint minor units (paise for INR), never floating point.

create table accounts (
    id            uuid primary key,
    owner_ref     text        not null,
    type          text        not null check (type in ('USER', 'SYSTEM')),
    currency      char(3)     not null check (currency ~ '^[A-Z]{3}$'),
    balance_minor bigint      not null default 0,
    version       bigint      not null default 0,           -- optimistic locking
    created_at    timestamptz not null default now(),
    -- User wallets can never go negative; system accounts (e.g. the payment
    -- gateway clearing account) are the other side of every top-up and may.
    constraint user_balance_non_negative check (type = 'SYSTEM' or balance_minor >= 0),
    constraint accounts_owner_currency_unique unique (owner_ref, currency, type)
);

create table journal_entries (
    id         uuid primary key,
    kind       text        not null check (kind in ('TOP_UP', 'TRANSFER', 'REFUND', 'ADJUSTMENT')),
    currency   char(3)     not null,
    reference  text,
    created_at timestamptz not null default now()
);

create table postings (
    id               bigint generated always as identity primary key,
    journal_entry_id uuid        not null references journal_entries (id),
    account_id       uuid        not null references accounts (id),
    amount_minor     bigint      not null check (amount_minor <> 0), -- + credit, - debit
    created_at       timestamptz not null default now()
);
create index postings_account_idx on postings (account_id, created_at);
create index postings_journal_idx on postings (journal_entry_id);

-- Double-entry invariant, enforced by the database at COMMIT: every journal
-- entry has at least two postings, they sum to zero, and every posting's
-- account uses the entry's currency. Deferred so a service can insert the
-- legs one by one inside a transaction.
create function check_journal_balanced() returns trigger
language plpgsql as $$
declare
    entry_id  uuid := case when tg_op = 'DELETE' then old.journal_entry_id else new.journal_entry_id end;
    legs      int;
    total     bigint;
    mismatched int;
begin
    select count(*), coalesce(sum(p.amount_minor), 0),
           count(*) filter (where a.currency <> j.currency)
      into legs, total, mismatched
      from postings p
      join accounts a on a.id = p.account_id
      join journal_entries j on j.id = p.journal_entry_id
     where p.journal_entry_id = entry_id;

    if legs > 0 and (legs < 2 or total <> 0) then
        raise exception 'journal entry % is unbalanced (% legs, sum %)', entry_id, legs, total
            using errcode = 'check_violation';
    end if;
    if mismatched > 0 then
        raise exception 'journal entry % mixes currencies', entry_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger postings_balanced
    after insert or update or delete on postings
    deferrable initially deferred
    for each row execute function check_journal_balanced();

-- Ledger history is append-only: corrections are new reversing entries.
create function reject_ledger_mutation() returns trigger
language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name using errcode = 'insufficient_privilege';
end;
$$;
create trigger postings_append_only before update or delete on postings
    for each row execute function reject_ledger_mutation();
create trigger journal_entries_append_only before update or delete on journal_entries
    for each row execute function reject_ledger_mutation();

-- Idempotency-Key support: a replay returns the stored response; the same
-- key with a different request body is rejected.
create table idempotency_keys (
    scope           text        not null,   -- caller identity
    idem_key        text        not null,
    request_hash    text        not null,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz not null default now(),
    primary key (scope, idem_key)
);

-- Transactional outbox: events are written in the same transaction as the
-- ledger change and published later by a poller.
create table outbox (
    id             bigint generated always as identity primary key,
    aggregate_type text        not null,
    aggregate_id   uuid        not null,
    event_type     text        not null,
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);
create index outbox_unpublished_idx on outbox (id) where published_at is null;
