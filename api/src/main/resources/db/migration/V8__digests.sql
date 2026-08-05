-- ---------------------------------------------------------------------------
-- V8 — the weekly digest: the last piece of sample content in the app
-- ---------------------------------------------------------------------------
-- One row per (user, week), generated on demand rather than by a cron job:
-- `GET /v1/digest` enqueues generation the first time a user asks for a week
-- that has none, and every request after that reads the cached row. No
-- scheduler exists yet, so nobody pays for a digest nobody opens.
--
-- Shape decisions worth keeping:
--
--   * **`week_start` matches `UsageService.weekStart()`** — the Monday of the
--     current ISO week, UTC. One definition of "this week" for the whole app
--     rather than a second boundary invented to match, which is exactly the
--     kind of drift that made `saveTitle()` and `search_tsv` disagree earlier.
--
--   * **A full overwrite on conflict, not an accumulation.** The shopping-list
--     Act taught this the hard way: an increment that runs twice on a re-
--     delivered job is wrong twice, where a full replace of the same inputs is
--     wrong zero times. A digest is a summary *of* the week's saves, not a
--     running total, so recomputing it is idempotent by construction as long
--     as the write replaces rather than adds.
-- ---------------------------------------------------------------------------

create table digests (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references profiles (id) on delete cascade,
    week_start date        not null,
    summary    text        not null,
    save_count int         not null,
    created_at timestamptz not null default now()
);

-- At most one digest per user per week — the upsert target for the handler's
-- "regenerate, don't accumulate" write.
create unique index digests_user_week_idx on digests (user_id, week_start);

alter table digests enable row level security;

create policy digests_self on digests
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
