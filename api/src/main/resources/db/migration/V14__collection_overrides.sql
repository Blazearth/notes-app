-- ---------------------------------------------------------------------------
-- V14 — collection_overrides: the user's explicit curation over the derived
-- collection view (K4 "identity upgrades")
-- ---------------------------------------------------------------------------
-- See docs/knowledge-collections.md ("Stored state", V14 sketch, and K4's
-- alias-resolution answer (b)). This is the only other thing that can't be
-- derived, alongside entity_states (V13) — everything else the collection
-- tree shows is recomputed per request from `saves`.
--
-- Three override_type values, applied as a post-processing step over the
-- pure merge core, never woven into it:
--   'entity_merge'      subject_key = the losing entityKey, payload
--                        {"into": "<winning entityKey>"} — the alias fix
--                        stated as a known limit of Entities.key's string
--                        rule: "Shingeki no Kyojin" and "Attack on Titan"
--                        collide under no normalization rule, but a user who
--                        knows they're the same thing can say so.
--   'entity_rename'     subject_key = entityKey, payload {"name": "..."}
--   'collection_rename' subject_key = a CollectionNode id (a type, or
--                        "type~facet-slug"), payload {"name": "..."}
--
-- Deliberately not four override_types: the doc's sketch also lists
-- merge-undo/split and pin. Undo is a DELETE of an 'entity_merge' row, not a
-- fourth type. Pin is left out of this table entirely and rides
-- entity_states' existing `state` jsonb instead (`state.pinned`) — it is a
-- per-(user, entity) boolean exactly like `done`/`rating`, and reusing that
-- proven mechanism costs nothing new here.
create table collection_overrides (
    user_id       uuid not null references profiles (id) on delete cascade,
    override_type text not null,
    subject_key   text not null,
    payload       jsonb not null,
    updated_at    timestamptz not null default now(),
    primary key (user_id, override_type, subject_key)
);

create trigger collection_overrides_set_updated_at
    before update on collection_overrides
    for each row execute function set_updated_at();

-- Defence in depth, not the API's boundary — see V1's note on this.
alter table collection_overrides enable row level security;

create policy collection_overrides_own on collection_overrides
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
