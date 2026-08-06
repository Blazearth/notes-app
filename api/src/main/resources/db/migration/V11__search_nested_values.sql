-- ---------------------------------------------------------------------------
-- V11 — index nested structured_data values without indexing their keys
-- ---------------------------------------------------------------------------
-- The registry gained nested object arrays on 2026-08-07 (a workout's
-- exercises, a recipe's {name, quantity, note} ingredients). V4's weight-C
-- expression extracts top-level values only:
--
--     jsonb_path_query_array(structured_data, '$.*')
--
-- An array of OBJECTS is one of those top-level values, and `::text`
-- serialises it whole — including its keys. That would put "sets", "reps",
-- "rest" and "name" into the index of every new workout and recipe, exactly
-- the keys-as-shared-terms failure V3 chose `$.*` to avoid ("rest" is a
-- plausible real query; matching every workout with it is noise, not recall).
--
-- The replacement walks the whole tree and keeps only scalar leaves:
--
--     strict $.** ? (@.type() != "object" && @.type() != "array"
--                    && @.type() != "null")
--
-- `strict` matters: lax mode auto-unwraps arrays during `.**` traversal and
-- produces duplicate matches. Verified against the live database before
-- landing (2026-08-07, session pooler), not assumed:
--
--   * nested workout  -> ["Push Day", "dumbbells", "control the negative",
--                         "Flat dumbbell press", "8-10", "4", ...]  — no keys
--   * flat legacy row -> same value set the old `$.*` produced
--   * accepted inside a GENERATED column (immutability), TSV as expected
--
-- Weights A and B are unchanged from V4.
-- ---------------------------------------------------------------------------

drop index if exists saves_search_tsv_idx;

alter table saves
    drop column if exists search_tsv;

alter table saves
    add column search_tsv tsvector generated always as (
        -- A: the identifier. `title` for most types, `name` for place.
        setweight(to_tsvector('english',
                              replace(coalesce(structured_data ->> 'title',
                                               structured_data ->> 'name',
                                               ''), '[unclear]', '')), 'A')
            ||
            -- B: written prose — the user's caption and the model's one-line
            -- description, under whichever name its schema uses.
        setweight(to_tsvector('english',
                              replace(
                                      coalesce(raw_caption, '') || ' ' ||
                                      coalesce(structured_data ->> 'summary', '') || ' ' ||
                                      coalesce(structured_data ->> 'synopsis', ''),
                                      '[unclear]', '')), 'B')
            ||
            -- C: the long tail — every scalar leaf at any depth, keys never.
        setweight(to_tsvector('english',
                              replace(coalesce(
                                              jsonb_path_query_array(structured_data,
                                                                     'strict $.** ? (@.type() != "object" && @.type() != "array" && @.type() != "null")')::text,
                                              ''), '[unclear]', '')), 'C')
        ) stored;

create index saves_search_tsv_idx on saves using gin (search_tsv);

comment on column saves.search_tsv is
    'Weighted FTS vector: A=title/name, B=caption/summary/synopsis, C=every scalar leaf of structured_data at any depth (keys never indexed). The [unclear] sentinel is stripped. Generated, so it can never be stale.';
