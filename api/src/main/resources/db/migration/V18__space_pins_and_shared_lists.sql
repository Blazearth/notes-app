-- ---------------------------------------------------------------------------
-- V18 — S4: pins, and a shopping list a whole Space can shop from
-- ---------------------------------------------------------------------------
-- Two unrelated-looking things in one migration because they are the same
-- answer to the same question: a Space's Overview has rows on it that nobody
-- can *derive*. "Current program" is a choice. "The list we're shopping from
-- for Saturday" is a shared object. Everything else on that tab (the merged
-- collections, the counts, the progress, the discussion) falls out of saves and
-- states that already exist — these two do not, and the honest way to have them
-- is to store the human decision rather than to synthesise one.
--
-- That distinction is the whole reason the workout example is a *pin*. A merged
-- "current program" assembled by the model from several push-day saves is
-- synthesis (docs/knowledge-collections.md's shape 3, banned), and its failure
-- mode is somebody following a routine no human ever wrote. A pin is a member
-- pointing at one save and saying "this one". Same row on the Overview, entirely
-- different claim behind it.


-- ---------------------------------------------------------------------------
-- space_pins
-- ---------------------------------------------------------------------------
-- `kind` and `subject` together address the pinned thing:
--
--     ('save',       '<save uuid>')                  — "current program"
--     ('collection', 'recommendation_list~anime')    — a node to the top
--
-- `subject` is text, not a uuid with a foreign key, because a collection node id
-- is derived (`CollectionService`'s `type~axis~value` path) and references no
-- row anywhere. Both kinds therefore share one nullable-free column, and a pin
-- whose subject has stopped existing is filtered out on read rather than being
-- prevented at write time — the same call `collection_overrides` and V17's
-- `entity_comments` both make, and for the same reason: an entity or a node is
-- a *view* over today's saves, so there is nothing to point a constraint at.
--
-- `kind` is free text with no check constraint, matching `space_activity.type`
-- and `saves.knowledge_type`: the vocabulary lives in `SpacePinService`, so
-- adding a pin kind must not be a migration.
--
-- `payload` is where a kind's own detail rides — the doc's cooking schedule
-- ("Saturday: Lasagna") is a save pin with `{"date": "2026-08-15"}`, not a new
-- calendar feature and not a new table.
create table space_pins (
    id         uuid primary key default gen_random_uuid(),
    space_id   uuid        not null references spaces (id) on delete cascade,
    kind       text        not null,
    subject    text        not null,
    payload    jsonb       not null default '{}'::jsonb,
    -- Who chose it. Shown on the Overview, because "Aryan pinned this" is what
    -- makes a pin a decision somebody made rather than a thing the app decided.
    created_by uuid        not null references profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Pinning the same thing twice is one pin, not two. The upsert in
-- `SpacePinService.pin` targets this index, which is also what makes a
-- double-tapped pin harmless without any dedupe machinery.
create unique index space_pins_unique on space_pins (space_id, kind, subject);

create index space_pins_space_idx on space_pins (space_id, created_at);

create trigger space_pins_set_updated_at
    before update on space_pins
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- shopping_lists gains a Space
-- ---------------------------------------------------------------------------
-- V5 built one open list per *user*, which was right: people shop once for
-- several recipes. A Recipe Space wants the same thing one level up — four
-- people planning Saturday need one list, not four.
--
-- `null` means personal, exactly as today, so every existing row keeps its
-- meaning and no backfill is needed. `ShoppingListService.fold` needs no change
-- at all: it already stores each contributing recipe's own quantity and
-- recomputes the total, so two *people's* recipes merge by the same rule two of
-- one person's always did. That property was built to stop a re-delivered job
-- inflating garlic from 7 cloves to 10; it pays for this feature for free.
--
-- ON DELETE CASCADE, and the alternative is worse than it looks: `set null`
-- would turn a deleted Space's shared list into a *second* personal open list
-- for whoever created it, which the partial unique index below would reject —
-- so deleting a Space would fail on a constraint violation from a table nobody
-- deleting a Space is thinking about. A shared list without its Space has no
-- audience anyway.
alter table shopping_lists
    add column space_id uuid references spaces (id) on delete cascade;

-- V5's index said "at most one open list per user" and would now also forbid a
-- user having a personal list *and* a Space list open at once, which is the
-- ordinary case. Replaced by one index per scope.
drop index shopping_lists_one_open_per_user;

create unique index shopping_lists_one_open_per_user
    on shopping_lists (user_id)
    where status = 'open' and space_id is null;

create unique index shopping_lists_one_open_per_space
    on shopping_lists (space_id)
    where status = 'open' and space_id is not null;

-- The Space's own lookup. Partial, because the overwhelming majority of rows
-- are personal and carry no space at all.
create index shopping_lists_space_idx
    on shopping_lists (space_id)
    where space_id is not null;


-- ---------------------------------------------------------------------------
-- RLS, for parity with the rest of the schema.
-- ---------------------------------------------------------------------------
-- Not the API's access-control boundary — Spring connects as a role with
-- BYPASSRLS and authorisation lives in the service layer. These exist for any
-- path where a client reaches Postgres directly.
alter table space_pins enable row level security;

create policy space_pins_member_read on space_pins
    for select to authenticated
    using (exists (select 1 from space_members m
                   where m.space_id = space_pins.space_id
                     and m.user_id = (select auth.uid())));

-- V5's policies were written before a list could belong to a Space, so they say
-- "your list" and would hide a shared one from every member but its creator.
-- Widened to "yours, or your Space's" on both tables.
drop policy shopping_lists_self on shopping_lists;
drop policy shopping_list_items_self on shopping_list_items;

create policy shopping_lists_visible on shopping_lists
    for all to authenticated
    using (user_id = (select auth.uid())
           or exists (select 1 from space_members m
                      where m.space_id = shopping_lists.space_id
                        and m.user_id = (select auth.uid())))
    with check (user_id = (select auth.uid())
                or exists (select 1 from space_members m
                           where m.space_id = shopping_lists.space_id
                             and m.user_id = (select auth.uid())));

create policy shopping_list_items_visible on shopping_list_items
    for all to authenticated
    using (exists (select 1 from shopping_lists l
                   where l.id = list_id
                     and (l.user_id = (select auth.uid())
                          or exists (select 1 from space_members m
                                     where m.space_id = l.space_id
                                       and m.user_id = (select auth.uid())))))
    with check (exists (select 1 from shopping_lists l
                        where l.id = list_id
                          and (l.user_id = (select auth.uid())
                               or exists (select 1 from space_members m
                                          where m.space_id = l.space_id
                                            and m.user_id = (select auth.uid())))));
