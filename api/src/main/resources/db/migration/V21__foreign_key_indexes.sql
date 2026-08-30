-- ---------------------------------------------------------------------------
-- V21 — indexes for foreign keys that had none
-- ---------------------------------------------------------------------------
-- Found by an audit against Supabase's Postgres best-practices guide: each of
-- these columns is a foreign key (most `on delete cascade`/`set null`) with no
-- supporting index — not even as a non-leading column of a composite one. At
-- today's row counts every one of these is invisible; the first time a Space
-- or profile with real history is deleted, or one of these lookups runs
-- against a grown table, it silently becomes a sequential scan instead of an
-- index scan.

-- shopping_list_items.save_id — V5's own comment says provenance ("why is
-- this on my list?") must be answerable by save_id; nothing indexed it.
-- Partial because a merged item's save_id goes null (see V5), so most rows
-- past a certain age carry no value here at all.
create index shopping_list_items_save_idx
    on shopping_list_items (save_id)
    where save_id is not null;

-- space_invites.created_by — "who made this link" / cascade on profile delete.
create index space_invites_created_by_idx
    on space_invites (created_by);

-- space_pins.created_by — same shape as above.
create index space_pins_created_by_idx
    on space_pins (created_by);

-- space_activity.user_id — only space_id was indexed; "this member's activity"
-- and the cascade on profile delete both scan without this.
create index space_activity_user_idx
    on space_activity (user_id);

-- space_activity.save_id — nullable (see V7: null for space-level events), and
-- cascades when a save is deleted.
create index space_activity_save_idx
    on space_activity (save_id)
    where save_id is not null;

-- save_comments.user_id — only save_id was indexed; cascade on profile delete.
create index save_comments_user_idx
    on save_comments (user_id);

-- save_duplicates.duplicate_of — the unique constraint on (save_id,
-- duplicate_of) only covers save_id as a leading column. duplicate_of is
-- itself a foreign key into saves with its own cascade, and "what points at
-- this save as a duplicate" is an unindexed lookup without this.
create index save_duplicates_duplicate_of_idx
    on save_duplicates (duplicate_of);

-- entity_comments.user_id — only space_id-leading indexes existed; cascade on
-- profile delete.
create index entity_comments_user_idx
    on entity_comments (user_id);
