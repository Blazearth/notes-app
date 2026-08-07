/**
 * The entity key, ported from `Entities.java` (`api/.../collection/Entities.java`).
 *
 * Client-side use is narrow and deliberate: the server is the merge
 * authority (`GET /v1/collections/{type}` already returns each entity's own
 * `entityKey`), so most screens never compute one. The one place that does
 * is `detailModel.ts`'s dual-read for `recommendation_list` items on the
 * save detail screen — it needs to look a save's own item up in a
 * separately-fetched list of collection entities, and the lookup key has to
 * match what the server would have computed for the same (kind, name).
 *
 * Kept in step with the Java version by hand; `EntitiesTest.java` is the
 * source of truth for the normalize spec if the two drift.
 */

const KIND_NAMESPACES: Record<string, string> = {
  film: 'screen',
  movie: 'screen',
  tv: 'screen',
  series: 'screen',
  anime: 'screen',
  show: 'screen',
  book: 'book',
  game: 'game',
  place: 'place',
  sight: 'place',
  restaurant: 'place',
  hotel: 'place',
  area: 'place',
  product: 'product',
  music: 'audio',
  podcast: 'audio',
  task: 'task',
};

const LEADING_ARTICLES = new Set(['the', 'a', 'an']);

/** `kind`/`medium` values that collapse into one namespace, so a game and a film sharing a title don't collide. */
export function namespace(kind: string | null | undefined): string {
  if (!kind) return 'other';
  const trimmed = kind.trim().toLowerCase();
  if (!trimmed) return 'other';
  return KIND_NAMESPACES[trimmed] ?? trimmed;
}

/** Unicode NFKC → casefold → trim → collapse whitespace → strip surrounding punctuation → strip a leading article. */
export function normalize(name: string | null | undefined): string {
  if (!name) return '';
  let s = name.normalize('NFKC').toLowerCase().trim();
  s = s.replace(/\s+/g, ' ');
  s = s.replace(/^[\p{P}\s]+/u, '').replace(/[\p{P}\s]+$/u, '');

  const firstSpace = s.indexOf(' ');
  if (firstSpace > 0 && LEADING_ARTICLES.has(s.slice(0, firstSpace))) {
    s = s.slice(firstSpace + 1);
  }
  return s;
}

/**
 * `kindNamespace + ":" + normalize(name)` — see `Entities.key` server-side.
 *
 * K4: when `canonicalId` is present (a `tmdbId` written by `TmdbEnricher`/
 * `RecommendationListEnricher` on a confident match), it overrides the
 * string key entirely — the alias fix for titles that share no words at
 * all, exactly `Entities.key(String, String, String)`'s three-arg overload.
 */
export function entityKey(
  kind: string | null | undefined,
  name: string | null | undefined,
  canonicalId?: string | null,
): string {
  if (canonicalId && canonicalId.trim()) {
    return `tmdb:${canonicalId.trim()}`;
  }
  return `${namespace(kind)}:${normalize(name)}`;
}
