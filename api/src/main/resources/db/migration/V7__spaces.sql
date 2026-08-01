-- ---------------------------------------------------------------------------
-- V7 — Spaces: invites, activity, comments, votes, and duplicate suggestions
-- ---------------------------------------------------------------------------
-- V1 created `spaces` and `space_members` and nothing has written to them
-- since. This adds the four tables collaboration actually needs, plus the one
-- that carries the differentiating feature: embedding-similarity duplicate
-- detection, which no competitor in the teardown does better than exact-URL
-- matching.
--
-- Shape decisions worth keeping:
--
--   * **An invite is a row, not a signed token.** A JWT-style invite cannot be
--     revoked without a denylist, which is the same table with more steps —
--     and revoking a link someone posted in a group chat is the single most
--     likely thing an owner will want to do.
--
--   * **`uses` is a counter with a ceiling, not a boolean.** "One person can
--     join" and "anyone with the link can join" are the same feature with
--     different numbers; a null `max_uses` is unlimited.
--
--   * **Votes are a row per (save, user), not a tally on the save.** A stored
--     total cannot answer "did I already vote?" and drifts the moment a
--     retry double-counts — the same trap the shopping list hit, where storing
--     a running total instead of each contributor's own quantity silently
--     inflated garlic from 7 cloves to 10 on a re-delivered job.
--
--   * **Duplicate detection writes a suggestion, never a merge.** Two people
--     saving the same restaurant from different URLs is a guess, however good
--     the cosine distance is, and silently merging someone's save is not
--     recoverable from the client.
-- ---------------------------------------------------------------------------


-- ---------------------------------------------------------------------------
-- space_invites — join by link or QR
-- ---------------------------------------------------------------------------
create table space_invites (
    id         uuid primary key default gen_random_uuid(),
    space_id   uuid        not null references spaces (id) on delete cascade,

    -- What goes in the link and the QR code. Generated with a CSPRNG in the
    -- application, not here: pgcrypto's presence on a Supabase project is not
    -- something to bet an access-control primitive on.
    code       text        not null unique,

    -- The role a joiner receives. An invite cannot grant `owner` — checked in
    -- the service layer as well, but stated here so the constraint survives
    -- anyone writing to this table by hand.
    role       text        not null default 'viewer' check (role in ('editor', 'viewer')),

    created_by uuid        not null references profiles (id) on delete cascade,

    -- Null means no expiry / unlimited uses respectively.
    expires_at timestamptz,
    max_uses   int check (max_uses is null or max_uses > 0),
    uses       int         not null default 0,

    -- Revocation is a flag rather than a delete, so an owner can still see
    -- that a link existed and who made it.
    revoked    boolean     not null default false,
    created_at timestamptz not null default now()
);

create index space_invites_space_idx on space_invites (space_id, created_at desc);


-- ---------------------------------------------------------------------------
-- space_activity — the feed, deliberately sparse
-- ---------------------------------------------------------------------------
-- Meaningful events only: joined, saved, completed, commented, voted. NOT
-- "viewed", "opened", or any per-scroll signal. An activity feed that logs
-- everything is noise nobody reads, and it is also the table that grows
-- fastest on a 500 MB free tier.
create table space_activity (
    id         uuid primary key default gen_random_uuid(),
    space_id   uuid        not null references spaces (id) on delete cascade,
    user_id    uuid        not null references profiles (id) on delete cascade,
    -- Null for events about the space itself rather than a save (a join).
    save_id    uuid references saves (id) on delete cascade,
    type       text        not null,
    payload    jsonb       not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index space_activity_space_idx on space_activity (space_id, created_at desc);


-- ---------------------------------------------------------------------------
-- save_comments
-- ---------------------------------------------------------------------------
create table save_comments (
    id         uuid primary key default gen_random_uuid(),
    save_id    uuid        not null references saves (id) on delete cascade,
    user_id    uuid        not null references profiles (id) on delete cascade,
    body       text        not null check (length(btrim(body)) between 1 and 2000),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index save_comments_save_idx on save_comments (save_id, created_at);

create trigger save_comments_set_updated_at
    before update on save_comments
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- save_votes
-- ---------------------------------------------------------------------------
-- One row per (save, user) with a composite PK, so "have I voted?" is a
-- lookup and changing a vote is an upsert rather than arithmetic.
create table save_votes (
    save_id    uuid        not null references saves (id) on delete cascade,
    user_id    uuid        not null references profiles (id) on delete cascade,
    -- -1 or 1. Removing a vote deletes the row rather than storing a 0, so the
    -- count is `sum(value)` with no special case.
    value      smallint    not null check (value in (-1, 1)),
    created_at timestamptz not null default now(),
    primary key (save_id, user_id)
);

create index save_votes_save_idx on save_votes (save_id);


-- ---------------------------------------------------------------------------
-- save_duplicates — the merge suggestion
-- ---------------------------------------------------------------------------
-- Written when a save lands in a shared Space and its embedding is close to
-- one already there. A suggestion, never an action: see the header.
create table save_duplicates (
    id           uuid primary key default gen_random_uuid(),
    space_id     uuid        not null references spaces (id) on delete cascade,
    -- The newly arrived save, and the one already in the Space it resembles.
    save_id      uuid        not null references saves (id) on delete cascade,
    duplicate_of uuid        not null references saves (id) on delete cascade,
    -- Cosine distance at the time of detection, kept so the threshold can be
    -- tuned against real pairs later rather than re-guessed. The same reason
    -- the search cutoff's measurements are written down.
    distance     numeric(6, 5) not null,
    status       text        not null default 'suggested'
                     check (status in ('suggested', 'dismissed', 'merged')),
    created_at   timestamptz not null default now(),

    -- One suggestion per pair. Without this, every re-run of the detector
    -- adds another row for the same two saves.
    unique (save_id, duplicate_of)
);

create index save_duplicates_space_idx on save_duplicates (space_id, status, created_at desc);


-- ---------------------------------------------------------------------------
-- RLS — defence in depth, not the API's boundary
-- ---------------------------------------------------------------------------
-- As everywhere else: Spring connects with BYPASSRLS and authorisation lives
-- in the service layer, keyed off the JWT `sub`. These policies exist for any
-- path where a client reaches Postgres directly.
alter table space_invites  enable row level security;
alter table space_activity enable row level security;
alter table save_comments  enable row level security;
alter table save_votes     enable row level security;
alter table save_duplicates enable row level security;

-- Members can read their Space's activity.
create policy space_activity_member_read on space_activity
    for select to authenticated
    using (exists (select 1 from space_members m
                   where m.space_id = space_activity.space_id
                     and m.user_id = (select auth.uid())));

-- Comments and votes follow the save: readable if the save is.
create policy save_comments_visible on save_comments
    for select to authenticated
    using (exists (select 1 from saves s
                   where s.id = save_comments.save_id
                     and (s.user_id = (select auth.uid())
                          or exists (select 1 from space_members m
                                     where m.space_id = s.space_id
                                       and m.user_id = (select auth.uid())))));

create policy save_comments_own_write on save_comments
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

create policy save_votes_own on save_votes
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
