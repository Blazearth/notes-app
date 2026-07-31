-- ---------------------------------------------------------------------------
-- V5 — the shopping list: the one Act, done well
-- ---------------------------------------------------------------------------
-- Turning a saved recipe into something you actually shop from is the first
-- feature that makes Weavr more than a nicer bookmark app. Everything before
-- this extracts structure; this is the first thing that *spends* it.
--
-- Shape decisions worth keeping:
--
--   * **One open list per user, not one per recipe.** People shop once for
--     several recipes, and a per-recipe list would push the merging onto them —
--     which is the whole job. A partial unique index enforces "at most one open
--     list" rather than application code remembering to check.
--
--   * **Items keep their provenance** (`save_id`). "Why is fish sauce on my
--     list?" has to be answerable, and it also lets a recipe be un-added later
--     without guessing which lines came from it.
--
--   * **`category` is free text, not an enum.** Same reasoning as
--     `saves.knowledge_type`: the aisle vocabulary lives in a prompt in code,
--     so widening it must not be a migration.
--
--   * **Quantity is text, deliberately.** Recipes say "a pinch", "2-3 cloves",
--     "1 tbsp + extra for greasing". Forcing that into numeric loses the half
--     of it that a shopper actually reads, and the arithmetic it would enable
--     (adding 2 cloves to 3 cloves) is exactly what the model already does when
--     it merges.
-- ---------------------------------------------------------------------------

create table shopping_lists (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references profiles (id) on delete cascade,
    status     text        not null default 'open'
                   check (status in ('open', 'archived')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- At most one open list per user. Partial, because archived lists accumulate
-- and there can be any number of them.
create unique index shopping_lists_one_open_per_user
    on shopping_lists (user_id)
    where status = 'open';

create index shopping_lists_user_idx on shopping_lists (user_id, created_at desc);

create trigger shopping_lists_set_updated_at
    before update on shopping_lists
    for each row execute function set_updated_at();


create table shopping_list_items (
    id       uuid primary key default gen_random_uuid(),
    list_id  uuid not null references shopping_lists (id) on delete cascade,

    -- Which recipe put this here. Null once an item has been merged from more
    -- than one recipe — `sources` carries the full set in that case.
    save_id  uuid references saves (id) on delete set null,
    -- Every save that contributed to this line, so a merged item can still
    -- answer "why is this on my list?".
    sources  jsonb not null default '[]'::jsonb,

    name     text not null,
    -- Text, not numeric: "a pinch" and "2-3" are what recipes actually say.
    quantity text,
    unit     text,
    -- Free text keyed against a vocabulary in the prompt, not a database enum —
    -- adding an aisle must not be a migration.
    category text not null default 'other',

    checked    boolean     not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index shopping_list_items_list_idx on shopping_list_items (list_id, category, name);

create trigger shopping_list_items_set_updated_at
    before update on shopping_list_items
    for each row execute function set_updated_at();


-- ---------------------------------------------------------------------------
-- RLS, for parity with the rest of the schema.
-- ---------------------------------------------------------------------------
-- Not the API's access-control boundary — Spring connects as a role with
-- BYPASSRLS and authorisation lives in the service layer, keyed off the JWT
-- `sub`. These exist for any path where a client reaches Postgres directly.
alter table shopping_lists      enable row level security;
alter table shopping_list_items enable row level security;

create policy shopping_lists_self on shopping_lists
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

create policy shopping_list_items_self on shopping_list_items
    for all to authenticated
    using (exists (select 1 from shopping_lists l
                   where l.id = list_id and l.user_id = (select auth.uid())))
    with check (exists (select 1 from shopping_lists l
                        where l.id = list_id and l.user_id = (select auth.uid())));
