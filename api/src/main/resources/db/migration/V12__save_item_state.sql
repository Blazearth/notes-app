-- ---------------------------------------------------------------------------
-- V12 — save_item_states: the one mechanism behind every type's interactivity
-- ---------------------------------------------------------------------------
-- Exercise ticks, checklist items, watch status + rating, reading progress —
-- all the same table and the same upsert. See docs/next-phases.md §4.1.
--
-- Identity is the array index (item_path = 'exercises[2]', 'items[0]', ''
-- for whole-save state). Safe *because* structured_data is immutable — there
-- is no reprocess path, so `exercises[2]` today is `exercises[2]` forever. If
-- a reprocess path is ever built, this is the first thing it breaks; that
-- migration pays for content-hash identity then, not now.
--
-- State is per (save, user), not per save: two Space members tick their own
-- copy of the same shared checklist independently, the same way save_votes
-- (V7) is a row per (save, user) rather than a tally.
create table save_item_states (
    save_id    uuid not null references saves (id) on delete cascade,
    user_id    uuid not null references profiles (id) on delete cascade,
    item_path  text not null,
    state      jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (save_id, user_id, item_path)
);

-- Serves the batched feed read: all of one user's states across a page of
-- saves in one query, keyed the same way the PK is.
create index save_item_states_user_save_idx on save_item_states (user_id, save_id);

create trigger save_item_states_set_updated_at
    before update on save_item_states
    for each row execute function set_updated_at();

-- Defence in depth, not the API's boundary — see V1's note on this.
alter table save_item_states enable row level security;

create policy save_item_states_own on save_item_states
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
