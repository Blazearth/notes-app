-- ---------------------------------------------------------------------------
-- V15 — sync primitives: delta cursors and tombstones
-- ---------------------------------------------------------------------------
-- L4 of docs/local-first.md. Until this migration the API had *zero* sync
-- primitives — no `since`, no cursor, no ETag, no `@Version`, no tombstone and
-- no `(user_id, updated_at)` index anywhere — so the client's only honest
-- option was a full fetch-and-replace of every table on every launch. This is
-- what turns that into a window.
--
-- Two halves, and the second is the one that cannot be worked around:
--
--   * **Cursors.** Every table below already had a trigger-maintained
--     `updated_at` (`set_updated_at()`, V1) except `space_members`, which had
--     only `joined_at` — a membership whose role changed was invisible to any
--     delta. The indexes make `where updated_at > ?` an index range scan
--     rather than the sequential scan it is today.
--
--   * **Tombstones.** A delta can carry a row that changed; it cannot carry a
--     row that stopped existing. There are seven hard-delete paths in this
--     codebase and none of them left a record, so a Space deleted while a
--     client was offline would live in that client's cache forever. Deletes
--     are the one part of sync that has to be written down at delete time.
--
-- On the cursor's shape: the plan sketched a keyset on `(updated_at, id)`, and
-- that is impossible for most of these tables — `save_item_states`,
-- `entity_states`, `collection_overrides` and `space_members` have composite
-- primary keys and no scalar `id` at all. So the cursor is the timestamp
-- alone, and `SyncService` guarantees progress by always closing a capped page
-- on a whole-timestamp boundary (see `SyncWindow`). The indexes are therefore
-- on `(scope, updated_at)`; the trailing `id` the plan asked for would never be
-- used.

-- ---------------------------------------------------------------------------
-- cursors
-- ---------------------------------------------------------------------------

-- The delta's main event. V1's index is (user_id, created_at desc), which the
-- feed uses and a `where updated_at > ?` cannot.
create index saves_user_updated_idx on saves (user_id, updated_at);

-- Scoped by membership rather than by user, so the index is on the space.
create index spaces_updated_idx on spaces (updated_at);

create index save_item_states_user_updated_idx on save_item_states (user_id, updated_at);
create index entity_states_user_updated_idx on entity_states (user_id, updated_at);
create index collection_overrides_user_upd_idx on collection_overrides (user_id, updated_at);

-- shopping_list_items is scoped through its list, which is scoped by user.
create index shopping_list_items_list_upd_idx on shopping_list_items (list_id, updated_at);

-- `space_members` had no `updated_at` at all — only `joined_at`, which never
-- moves. `default now()` backfills every existing row to the migration's
-- timestamp, which is correct rather than merely convenient: a client with no
-- cursor gets them all on its first page anyway.
alter table space_members add column updated_at timestamptz not null default now();

create trigger space_members_set_updated_at
    before update on space_members
    for each row execute function set_updated_at();

-- Two indexes, not one. The client asks "every member of every Space I am in",
-- which walks by space; a per-user index would serve only "my own membership".
create index space_members_space_updated_idx on space_members (space_id, updated_at);
create index space_members_user_updated_idx on space_members (user_id, updated_at);


-- ---------------------------------------------------------------------------
-- tombstones
-- ---------------------------------------------------------------------------
-- One row per (audience member, deleted thing). Per-user rather than global
-- because the audience is what makes a tombstone useful: deleting a Space has
-- to reach every member's cache, not just the owner's, and the members have to
-- be read *before* the delete or they are already gone.
--
-- `entity_id` is text rather than uuid because not everything deletable has a
-- uuid: an item state is addressed by (save id, item path) and a collection
-- override by (override type, subject key). The composite forms are documented
-- on `TombstoneService`.
--
-- Nothing prunes this table yet. It grows only on deletes, which are rare here
-- (there is no delete-a-save path at all), and a retention policy needs a
-- decision about how long a client may stay offline and still be trusted to
-- hold a consistent cache — that decision is not this migration's to make.
create table tombstones (
    id          bigserial primary key,
    user_id     uuid        not null references profiles (id) on delete cascade,
    entity_type text        not null,
    entity_id   text        not null,
    deleted_at  timestamptz not null default now()
);

-- Serves the delta's window: one user's deletions inside a timestamp range.
create index tombstones_user_deleted_idx on tombstones (user_id, deleted_at, id);

-- Defence in depth, not the API's boundary — see V1's note on this.
alter table tombstones enable row level security;

create policy tombstones_own on tombstones
    for select to authenticated
    using (user_id = (select auth.uid()));
