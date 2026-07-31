-- ---------------------------------------------------------------------------
-- V3 — make hybrid search actually able to find things
-- ---------------------------------------------------------------------------
-- V1 shipped `search_tsv` as a generated column over exactly three fields:
--
--     raw_caption, structured_data->>'title', structured_data->>'summary'
--
-- That is not enough, and the gap is not subtle:
--
--   * **`place` has no `title`.** Its name field is called `name`
--     (KnowledgeTypeRegistry) — the same trap that silently broke saveTitle()
--     on the mobile side. So a saved restaurant was completely unfindable by
--     its own name: searching "Noma" matched nothing at all.
--   * **`movie` has no `summary`** either; it has `synopsis`.
--   * **Every array field was invisible.** A recipe's `ingredients`, a place's
--     `highlights`, a movie's `genre` and `other`'s `tags` carry most of what
--     a user would actually type into a search box, and none of it was indexed.
--
-- The replacement is weighted rather than flat, so ranking can tell the
-- difference between a save *named* "Noma" and one that merely mentions it:
--
--     A  the title/name — the primary identifier
--     B  prose the user or the model wrote: caption, summary, synopsis
--     C  everything else in structured_data
--
-- ts_rank's default weights are {D=0.1, C=0.2, B=0.4, A=1.0}, so an A match
-- scores 5x a C match with no extra query-side work.
--
-- ---------------------------------------------------------------------------
-- Two facts about generated columns, both confirmed against the live database
-- (PG 17.6) rather than assumed, because getting either wrong fails at
-- migration time and leaves Flyway needing a repair:
--
--   1. `to_tsvector(text)` is only STABLE — it reads
--      default_text_search_config, which is a session setting. Only the
--      two-argument `to_tsvector(regconfig, text)` is IMMUTABLE and therefore
--      legal here. Every call below passes 'english' explicitly.
--
--   2. `jsonb_path_query_array(...)::text` IS immutable and IS accepted.
--      The obvious alternative, `structured_data::text`, is also accepted but
--      indexes the JSON *keys* alongside the values — the literal words
--      "name", "title" and "ingredients" would then match every single save.
--      `$.*` yields values only.
-- ---------------------------------------------------------------------------

drop index if exists saves_search_tsv_idx;

alter table saves
    drop column if exists search_tsv;

alter table saves
    add column search_tsv tsvector generated always as (
        -- A: the identifier. `title` for most types, `name` for place.
        setweight(to_tsvector('english',
                              coalesce(structured_data ->> 'title',
                                       structured_data ->> 'name',
                                       '')), 'A')
            ||
            -- B: written prose — the user's own caption, and the model's
            -- one-line description under whichever name its schema uses.
        setweight(to_tsvector('english',
                              coalesce(raw_caption, '') || ' ' ||
                              coalesce(structured_data ->> 'summary', '') || ' ' ||
                              coalesce(structured_data ->> 'synopsis', '')), 'B')
            ||
            -- C: the long tail — ingredients, steps, highlights, tags, genre,
            -- cuisine, address. Values only, never keys.
        setweight(to_tsvector('english',
                              coalesce(
                                      jsonb_path_query_array(structured_data, '$.*')::text,
                                      '')), 'C')
        ) stored;

create index saves_search_tsv_idx on saves using gin (search_tsv);


-- ---------------------------------------------------------------------------
-- Embedding coverage
-- ---------------------------------------------------------------------------
-- Which saves still need embedding, so the backfill is a query rather than a
-- full-table scan. Partial, because the steady state is "almost everything is
-- embedded" and a partial index over the remainder stays tiny.
create index saves_needs_embedding_idx on saves (created_at)
    where embedding is null and status = 'ready';


comment on column saves.search_tsv is
    'Weighted FTS vector: A=title/name, B=caption/summary/synopsis, C=all other structured_data values. Generated, so it can never be stale.';
