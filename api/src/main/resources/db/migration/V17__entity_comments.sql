-- ---------------------------------------------------------------------------
-- V17 — entity_comments: a remark about the *thing*, not about whichever Reel
--       happened to mention it
-- ---------------------------------------------------------------------------
-- S3 of docs/knowledge-spaces.md. `save_comments` (V7) attaches discussion to a
-- save, which was right when a Space was a folder of saves. Once the Space's
-- primary surface is the merged knowledge layer, "Blue Box starts slow but the
-- second half is worth it" is a remark about Blue Box — and Blue Box is an
-- entity assembled from two members' saves, so hanging that comment off one of
-- them buries it under a source the next reader has no reason to open.
--
-- Three shape decisions, each different from the tables it sits beside:
--
--   * **Space-scoped, unlike `entity_states` (V13).** State is per
--     `(user_id, entity_key)` and global — completing Your Name is a fact about
--     the person, which is exactly why it shows in every Space that contains
--     the title (and why the app says so out loud). A *remark to your friends*
--     is not like that: it was said in a room, and it belongs to that room. So
--     `space_id` is part of the key here and is deliberately absent there. The
--     two tables answering the same-looking question differently is the point,
--     not an inconsistency.
--
--   * **`entity_key` is text with no foreign key, and cannot have one.** An
--     entity is *derived* — `Entities.key` over the merged items of whatever
--     saves the Space holds today — so there is no row to reference. The
--     consequence is honest and bounded: a comment can outlive the entity it
--     was about (every save mentioning Blue Box leaves the Space) and simply
--     stops being reachable, the same way a `collection_overrides` row does.
--     Nothing breaks; a read filtered to today's derived key set just does not
--     return it.
--
--   * **No `updated_at` trigger and no edit path.** Comments are appended and
--     deleted, never rewritten — the same shape `save_comments` already has,
--     and `updated_at` exists only so this table could join the delta later
--     without a second migration.
--
-- Not in `GET /v1/sync`, for the same reason `save_comments` is not: scoping
-- "comments I can see" is a join across Spaces for what is a detail-screen
-- read. Deletions do ride the tombstone list, which is the half a full pull
-- handles worst.

create table entity_comments (
    id         uuid primary key default gen_random_uuid(),
    space_id   uuid        not null references spaces (id) on delete cascade,
    entity_key text        not null,
    user_id    uuid        not null references profiles (id) on delete cascade,
    body       text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- The thread read: one entity's comments, oldest first. Also serves the
-- Overview's "latest across the whole Space" read as a range scan on the
-- leading column.
create index entity_comments_space_entity_idx
    on entity_comments (space_id, entity_key, created_at);

-- The Overview's recent-discussion block, and the batched per-entity counts the
-- collection screen shows on each row.
create index entity_comments_space_created_idx
    on entity_comments (space_id, created_at desc);

create trigger entity_comments_set_updated_at
    before update on entity_comments
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- RLS, for parity with the rest of the schema.
-- ---------------------------------------------------------------------------
-- Not the API's access-control boundary — Spring connects as a role with
-- BYPASSRLS and authorisation lives in the service layer, keyed off the JWT
-- `sub`. These exist for any path where a client reaches Postgres directly.
alter table entity_comments enable row level security;

-- Readable by anyone in the Space; writable only as yourself. Deleting someone
-- else's comment is an owner-only action enforced in the service layer, which
-- RLS deliberately does not try to restate.
create policy entity_comments_member_read on entity_comments
    for select to authenticated
    using (exists (select 1 from space_members m
                   where m.space_id = entity_comments.space_id
                     and m.user_id = (select auth.uid())));

create policy entity_comments_own_write on entity_comments
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid())
                and exists (select 1 from space_members m
                            where m.space_id = entity_comments.space_id
                              and m.user_id = (select auth.uid())));
