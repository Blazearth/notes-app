/**
 * Which extracted field subdivides each knowledge type, and the
 * product-facing name for each type — a hand-kept port of
 * `api/.../common/KnowledgeFacets.java`.
 *
 * That Java file exists precisely because `GroupService` and
 * `CollectionService` both need this map and two copies would drift. The
 * client now derives both trees locally (see `@/groups/tree` and
 * `@/collections/merge`), so it needs the same map for the same reason —
 * hence one file here rather than one per consumer.
 *
 * The Java side is the source of truth if the two ever disagree.
 */

/**
 * Facet values are a closed-ish vocabulary the model already repeats —
 * cuisines and genres recur across saves, so they cluster. A free-text field
 * like `title` would produce one bucket per save, which is not a grouping
 * at all.
 */
export const FACETS: Readonly<Record<string, string>> = {
  recipe: 'cuisine',
  restaurant: 'cuisine',
  movie: 'genre',
  book: 'genre',
  place: 'cuisine',
  article: 'category',
  product: 'category',
  workout: 'category',
  other: 'category',
  recommendation_list: 'medium',
  checklist: 'category',
  itinerary: 'destination',
  course: 'subject',
  github_repo: 'language',
};

/**
 * Anything absent falls back to a title-cased version of the raw type,
 * because `knowledgeType` is free text from the model — a type this map has
 * never heard of must still get a sensible folder rather than disappearing
 * from the Library.
 */
export const DISPLAY_NAMES: Readonly<Record<string, string>> = {
  recipe: 'Recipes',
  movie: 'Watchlist',
  place: 'Places',
  restaurant: 'Restaurants',
  product: 'Shopping',
  article: 'Reading',
  workout: 'Workouts',
  book: 'Books',
  other: 'Other',
  recommendation_list: 'Recommendations',
  checklist: 'Checklists',
  itinerary: 'Itineraries',
  course: 'Courses',
  github_repo: 'Repos',
};

export function displayName(knowledgeType: string): string {
  return DISPLAY_NAMES[knowledgeType] ?? titleCase(knowledgeType);
}

export function titleCase(value: string): string {
  const trimmed = value.trim();
  return trimmed ? trimmed.charAt(0).toUpperCase() + trimmed.slice(1) : trimmed;
}

/**
 * `[unclear]` is the registry's sentinel for "the model could not tell", and
 * grouping by it would create a folder named after our own uncertainty — the
 * same reason V4 stripped it from the search vector.
 */
export function isUsableFacetValue(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.toLowerCase() !== '[unclear]';
}

/** URL-safe and stable: the same facet value always yields the same id. */
export function slug(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
}

export function normaliseType(type: string | null | undefined): string | null {
  if (!type || !type.trim()) return null;
  return type.trim().toLowerCase();
}
