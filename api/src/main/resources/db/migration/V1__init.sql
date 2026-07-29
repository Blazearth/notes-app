-- Weavr V1 — initial schema.
--
-- This migration is a ONE-WAY DOOR in one specific respect: the embedding
-- dimension below (1536) is baked into the DDL. Changing it later is a
-- migration *plus* a full re-embedding backfill. It is sized for
-- gemini-embedding-001 with outputDimensionality=1536 requested EXPLICITLY
-- (the model defaults to 3072).
--
-- Runs via the SESSION pooler (port 5432) or a direct connection — never the
-- transaction pooler. Flyway takes a session-level advisory lock and runs DDL
-- in transactions; both break under the transaction pooler.

-- pgvector. On Supabase this may already be installed into the `extensions`
-- schema, in which case this is a no-op and the `vector` type/opclasses
-- resolve through the postgres role's search_path ("$user", public, extensions).
create extension if not exists vector;


-- ---------------------------------------------------------------------------
-- shared trigger: keep updated_at honest
-- ---------------------------------------------------------------------------
create or replace function set_updated_at() returns trigger
language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;


-- ---------------------------------------------------------------------------
-- profiles — one row per auth user
-- ---------------------------------------------------------------------------
-- Rows are created lazily by the API on first authenticated request
-- (ProfileRepository.ensureExists), not by a trigger on auth.users. That keeps
-- this migration out of the auth schema and works for users who signed up
-- before the table existed.
create table profiles (
    id                     uuid primary key references auth.users (id) on delete cascade,
    display_name           text,
    avatar_url             text,
    revenuecat_customer_id text unique,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);

create trigger profiles_set_updated_at
    before update on profiles
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- spaces + membership
-- ---------------------------------------------------------------------------
create table spaces (
    id         uuid primary key default gen_random_uuid(),
    name       text not null,
    type       text not null default 'general',
    owner_id   uuid not null references profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index spaces_owner_id_idx on spaces (owner_id);

create trigger spaces_set_updated_at
    before update on spaces
    for each row execute function set_updated_at();

-- Composite PK, and a role enum rather than capability booleans.
create table space_members (
    space_id  uuid not null references spaces (id) on delete cascade,
    user_id   uuid not null references profiles (id) on delete cascade,
    role      text not null default 'viewer' check (role in ('owner', 'editor', 'viewer')),
    joined_at timestamptz not null default now(),
    primary key (space_id, user_id)
);

-- The PK covers (space_id, ...); this index serves "which spaces am I in".
create index space_members_user_id_idx on space_members (user_id);


-- ---------------------------------------------------------------------------
-- saves — the core table
-- ---------------------------------------------------------------------------
-- structured_data is JSONB, not per-type columns: ~20 knowledge types with
-- divergent schemas, and adding a type must not require a migration.
-- knowledge_type is deliberately free text for the same reason — it is keyed
-- against a registry in code, not a database enum.
create table saves (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references profiles (id) on delete cascade,
    space_id           uuid references spaces (id) on delete set null,

    source_type        text not null check (source_type in ('url', 'text', 'image', 'pdf', 'audio')),
    source_url         text,
    raw_caption        text,
    media_storage_path text,

    status             text not null default 'processing'
                           check (status in ('processing', 'pending', 'ready', 'failed')),
    knowledge_type     text,
    confidence         numeric(4, 3) check (confidence is null or confidence between 0 and 1),
    structured_data    jsonb not null default '{}'::jsonb,
    embedding          vector(1536),

    lifecycle_status   text not null default 'saved'
                           check (lifecycle_status in ('saved', 'planned', 'started', 'completed')),

    -- Which model actually served this save, so escalation rate is measurable
    -- rather than guessed.
    model_used         text,

    -- Failure UX: silent capture means nobody is watching when yt-dlp fails,
    -- so the notification is the entire failure experience. error_code keys a
    -- signature -> user-readable message table in code.
    error_code         text,
    error_message      text,

    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    constraint saves_has_content check (source_url is not null or raw_caption is not null)
);

create index saves_user_created_idx on saves (user_id, created_at desc);
create index saves_space_created_idx on saves (space_id, created_at desc) where space_id is not null;
create index saves_status_idx on saves (status) where status in ('processing', 'pending');

create trigger saves_set_updated_at
    before update on saves
    for each row execute function set_updated_at();

-- Half of hybrid search. Generated + stored, so it is never stale and costs no
-- application code. All functions used here are IMMUTABLE, which a generated
-- column requires.
alter table saves
    add column search_tsv tsvector generated always as (
        to_tsvector('english',
                    coalesce(raw_caption, '') || ' ' ||
                    coalesce(structured_data ->> 'title', '') || ' ' ||
                    coalesce(structured_data ->> 'summary', '')
        )
        ) stored;

create index saves_search_tsv_idx on saves using gin (search_tsv);

-- The other half. Cosine distance (<=>) matches normalised embedding vectors.
create index saves_embedding_idx on saves using hnsw (embedding vector_cosine_ops);


-- ---------------------------------------------------------------------------
-- save_stages — per-stage cache of pipeline output
-- ---------------------------------------------------------------------------
-- Lets a retry resume rather than re-run, so a redeploy mid-pipeline never
-- re-spends a Gemini request or re-downloads media.
create table save_stages (
    save_id    uuid not null references saves (id) on delete cascade,
    stage      text not null,
    payload    jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    primary key (save_id, stage)
);


-- ---------------------------------------------------------------------------
-- jobs — Postgres-backed queue, claimed with FOR UPDATE SKIP LOCKED
-- ---------------------------------------------------------------------------
create table jobs (
    id              uuid primary key default gen_random_uuid(),
    type            text not null,
    payload         jsonb not null default '{}'::jsonb,
    status          text not null default 'queued'
                        check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    priority        int not null default 0,

    -- Round-robin key, so one user cannot monopolise a 1-2 slot worker pool.
    group_id        text,

    -- Dedupes re-shares of the same content. Enqueue is ON CONFLICT DO NOTHING.
    idempotency_key text unique,

    attempts        int not null default 0,
    max_attempts    int not null default 5,

    -- Set by RetryAfterException (quota rejection) WITHOUT incrementing
    -- attempts. That distinction is what stops a Gemini quota rejection from
    -- burning the retry budget.
    run_after       timestamptz not null default now(),

    claimed_at      timestamptz,
    claimed_by      text,
    last_error      text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- Serves the claim query: status + run_after filter, priority ordering.
create index jobs_claim_idx on jobs (status, run_after, priority desc);
create index jobs_group_idx on jobs (group_id) where status = 'queued';

create trigger jobs_set_updated_at
    before update on jobs
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- AI budget + observability
-- ---------------------------------------------------------------------------
-- Every Gemini call, so escalation rate and spend are measured, not guessed.
create table gemini_calls (
    id            uuid primary key default gen_random_uuid(),
    save_id       uuid references saves (id) on delete set null,
    model         text not null,
    purpose       text not null,
    input_tokens  int,
    output_tokens int,
    confidence    numeric(4, 3),
    outcome       text not null,
    created_at    timestamptz not null default now()
);

create index gemini_calls_created_idx on gemini_calls (created_at desc);
create index gemini_calls_save_idx on gemini_calls (save_id);

-- The daily request counter. MUST live in Postgres, not memory: an in-memory
-- counter loses the day's consumption on every deploy and silently over-spends.
-- One row per (day, model) because each model has its own RPD pool — routing
-- across models multiplies daily capacity.
-- usage_date is stored in Google's reset timezone (US/Pacific), not UTC.
create table ai_budget_days (
    usage_date    date not null,
    model         text not null,
    requests_used int not null default 0,
    updated_at    timestamptz not null default now(),
    primary key (usage_date, model)
);


-- ---------------------------------------------------------------------------
-- monetisation
-- ---------------------------------------------------------------------------
-- Free-tier caps enforced in the worker: 20 AI saves/month, 1 Act/week.
create table usage_counters (
    user_id      uuid not null references profiles (id) on delete cascade,
    period_start date not null,
    saves_used   int not null default 0,
    acts_used    int not null default 0,
    primary key (user_id, period_start)
);

-- Written by the RevenueCat webhook. Entitlements are enforced server-side;
-- a client-only check is trivially bypassed and the gated resource is the
-- expensive one.
create table subscriptions (
    user_id                uuid primary key references profiles (id) on delete cascade,
    revenuecat_customer_id text,
    entitlement            text,
    status                 text not null default 'inactive',
    renews_at              timestamptz,
    updated_at             timestamptz not null default now()
);

create trigger subscriptions_set_updated_at
    before update on subscriptions
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
-- RLS is NOT the access-control boundary for API traffic. Spring connects as
-- the `postgres` role, which carries BYPASSRLS on Supabase, so these policies
-- do not apply to it — authorization for the API lives in the service layer,
-- keyed off the JWT `sub` claim.
--
-- These exist as defence in depth for any path where the client talks to
-- Postgres directly (PostgREST, Realtime). Enabling RLS with no matching
-- policy is a deny-all default, which is the behaviour we want.
--
-- NOTE: if API reads ever start returning zero rows unexpectedly, check that
-- the connecting role still has BYPASSRLS before looking anywhere else.
alter table profiles       enable row level security;
alter table spaces         enable row level security;
alter table space_members  enable row level security;
alter table saves          enable row level security;
alter table save_stages    enable row level security;
alter table usage_counters enable row level security;
alter table subscriptions  enable row level security;

-- Internal tables: no policies at all — deny-all to every client role.
alter table jobs            enable row level security;
alter table gemini_calls    enable row level security;
alter table ai_budget_days  enable row level security;

create policy profiles_self on profiles
    for all to authenticated
    using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy saves_owner on saves
    for all to authenticated
    using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- Members can read a space's saves; membership itself is readable to members.
create policy saves_space_member_read on saves
    for select to authenticated
    using (
    space_id is not null
        and exists (select 1
                    from space_members m
                    where m.space_id = saves.space_id
                      and m.user_id = (select auth.uid()))
    );

create policy space_members_self_read on space_members
    for select to authenticated
    using (user_id = (select auth.uid()));

create policy spaces_member_read on spaces
    for select to authenticated
    using (
    owner_id = (select auth.uid())
        or exists (select 1
                   from space_members m
                   where m.space_id = spaces.id
                     and m.user_id = (select auth.uid()))
    );

create policy usage_counters_self on usage_counters
    for select to authenticated
    using (user_id = (select auth.uid()));

create policy subscriptions_self on subscriptions
    for select to authenticated
    using (user_id = (select auth.uid()));
