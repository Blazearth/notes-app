-- ---------------------------------------------------------------------------
-- V13 — entity_states: per-user state for a merged entity, not a save item
-- ---------------------------------------------------------------------------
-- See docs/knowledge-collections.md ("Stored state") for the design. Watched
-- status and rating must survive Blue Box appearing in a fourth save
-- tomorrow, which V12's save_item_states (tied to one save's `items[3]`)
-- cannot do — this table is keyed on the entity, not the save.
--
-- Dual-read, no backfill: for recommendation_list items the client reads
-- entity_state ?? item_state (entity wins) and writes entity state from now
-- on. Old ticks recorded under save_item_states stay visible through that
-- fallback rather than being migrated — there is no reliable reverse map
-- from `items[3]` to an entity key without re-deriving it, and a wrong
-- backfill is worse than a stale tick. `workout` exercise completion stays
-- on save_item_states entirely: completing a set in one session is a
-- per-session fact, not a property of the "bench press" entity.
--
-- Per-user, not per-space, same reasoning as save_votes (V7) and
-- save_item_states (V12): two Space members work through a shared
-- watchlist independently.
create table entity_states (
    user_id    uuid not null references profiles (id) on delete cascade,
    entity_key text not null,
    state      jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, entity_key)
);

create trigger entity_states_set_updated_at
    before update on entity_states
    for each row execute function set_updated_at();

-- Defence in depth, not the API's boundary — see V1's note on this.
alter table entity_states enable row level security;

create policy entity_states_own on entity_states
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
