package com.weavr.api.enrich;

import java.util.Map;

/**
 * TMDB's genre ids, which its search endpoint returns instead of names.
 *
 * <p>Hard-coded rather than fetched from {@code /genre/movie/list}: the list has
 * been stable for years, it is small, and fetching it would mean either a
 * request per save or a cache with an invalidation story — both worse than a
 * map that is wrong at most by being incomplete. An unknown id is dropped, so
 * the failure mode of it going stale is a missing genre, not a wrong one.
 *
 * <p>Movie and TV ids are merged; they overlap without conflicting.
 */
final class TmdbGenres {

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(28, "action"),
            Map.entry(12, "adventure"),
            Map.entry(16, "animation"),
            Map.entry(35, "comedy"),
            Map.entry(80, "crime"),
            Map.entry(99, "documentary"),
            Map.entry(18, "drama"),
            Map.entry(10751, "family"),
            Map.entry(14, "fantasy"),
            Map.entry(36, "history"),
            Map.entry(27, "horror"),
            Map.entry(10402, "music"),
            Map.entry(9648, "mystery"),
            Map.entry(10749, "romance"),
            Map.entry(878, "sci-fi"),
            Map.entry(10770, "tv movie"),
            Map.entry(53, "thriller"),
            Map.entry(10752, "war"),
            Map.entry(37, "western"),
            Map.entry(10759, "action & adventure"),
            Map.entry(10762, "kids"),
            Map.entry(10763, "news"),
            Map.entry(10764, "reality"),
            Map.entry(10765, "sci-fi & fantasy"),
            Map.entry(10766, "soap"),
            Map.entry(10767, "talk"),
            Map.entry(10768, "war & politics"));

    private TmdbGenres() {
    }

    /** The genre name, or null for an id this map has never heard of. */
    static String name(int id) {
        return NAMES.get(id);
    }
}
