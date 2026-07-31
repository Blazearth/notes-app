-- ---------------------------------------------------------------------------
-- V4 — keep the [unclear] sentinel out of the search index
-- ---------------------------------------------------------------------------
-- V3 widened `search_tsv` to cover every value in structured_data, which was
-- the right call and is what finally made a saved restaurant findable by its
-- own name. It also swept in something it should not have. Inspecting the
-- generated vector for one real place row:
--
--     'copenhagen':7C 'dinner':2B 'dish':11C 'ferment':10C 'idea':3B
--     'moss':13C 'new':8C 'noma':1A,4C 'nordic':9C 'reindeer':12C
--     'restaur':5C 'unclear':6C
--                  ^^^^^^^^^^^
--
-- "[unclear]" is KnowledgeTypeRegistry's sentinel for information genuinely not
-- present in the content, and it appears in most saves — a recipe with no
-- stated prep time, a movie with no director, a place with no rating. Indexed,
-- it becomes a term that nearly every save shares.
--
-- This is the same mistake EmbeddingProfile deliberately avoids on the vector
-- side, where the sentinel is dropped rather than embedded, and it is wrong
-- here for the same reason: a token common to almost every document carries no
-- information, inflates every vector, and skews ts_rank's length
-- normalisation. It is a smaller problem in FTS than in embeddings — nobody
-- searches for "unclear" — but it costs one table rewrite to fix now and the
-- same rewrite plus a backfill to fix later.
--
-- Stripped with plain `replace()` rather than a helper function on purpose: a
-- generated column that depends on a user-defined function cannot have that
-- function altered afterwards, which trades today's readability for a wall the
-- next migration hits.
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
            -- C: the long tail — ingredients, steps, highlights, tags, genre,
            -- cuisine, address. Values only, never keys (see V3).
        setweight(to_tsvector('english',
                              replace(coalesce(
                                              jsonb_path_query_array(structured_data, '$.*')::text,
                                              ''), '[unclear]', '')), 'C')
        ) stored;

create index saves_search_tsv_idx on saves using gin (search_tsv);

comment on column saves.search_tsv is
    'Weighted FTS vector: A=title/name, B=caption/summary/synopsis, C=all other structured_data values. The [unclear] sentinel is stripped — it appears in most saves and carries no information. Generated, so it can never be stale.';
