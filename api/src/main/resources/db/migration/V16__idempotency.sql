-- ---------------------------------------------------------------------------
-- V16 — generalised idempotency for the three POSTs that are not replay-safe
-- ---------------------------------------------------------------------------
-- L5 of docs/local-first.md. `POST /v1/spaces`, `POST /v1/spaces/{id}/invites`
-- and `POST /v1/saves/{id}/comments` each create a row with a server-generated
-- id, so sending one twice creates two of them. That was survivable while every
-- caller was a person tapping a button once; it stops being survivable the
-- moment an offline queue retries a request whose response was lost, which is
-- exactly what L3's outbox does. `addComment` is already queued; the two Space
-- writes are the ones this migration exists to make queueable at all.
--
-- The shape is the standard one: a claim row keyed on (user, key), and the
-- response stored beside it so a replay returns the *same* answer rather than
-- merely avoiding a second write. Returning a 200 with no body, or a fresh
-- 409, would leave the client unable to finish an operation the server has
-- already completed — the retry would have "succeeded" and the caller would
-- still not have the invite code.
--
-- `endpoint` is stored rather than being part of the key. It is not there to
-- widen the key (a key reused across two endpoints is a client bug, and
-- IdempotencyService reports it as one rather than quietly running both) but so
-- that the mistake is *detectable* — without it, replaying a comment's stored
-- body at a Space-creation call site would deserialise into the wrong record and
-- fail somewhere far from the cause.
--
-- **POST /v1/saves deliberately does not move here.** It has had its own
-- column-based mechanism since V2 (`saves.idempotency_key` plus a partial unique
-- index), it is live-verified including the concurrent-retry race, and migrating
-- it would be schema risk for no behavioural gain. The inconsistency is
-- intentional; this comment exists so the next reader does not "fix" it.
--
-- Nothing prunes this table. It grows one row per idempotent write, which is a
-- small fraction of a low-volume workload, and a retention policy needs a
-- decision about how long a client may retry and still expect the stored answer
-- — the same open question the tombstone table carries, and not this migration's
-- to make either.
create table idempotency_keys (
    user_id       uuid        not null references profiles (id) on delete cascade,
    key           text        not null,
    endpoint      text        not null,
    -- Null between the claim and the response. In the ordinary single-
    -- transaction flow these two are written in one commit, so a null here is
    -- only ever visible to a concurrent inserter that has just been unblocked
    -- by a rollback — see IdempotencyService for why that case is a 409 rather
    -- than a second attempt at the work.
    status_code   int,
    response_body jsonb,
    created_at    timestamptz not null default now(),
    primary key (user_id, key)
);

-- Defence in depth, not the API's boundary — see V1's note on this. Nothing
-- reads this table from a client connection; the policy is here so that a
-- future direct read cannot see another user's stored responses.
alter table idempotency_keys enable row level security;

create policy idempotency_keys_own on idempotency_keys
    for select to authenticated
    using (user_id = (select auth.uid()));
