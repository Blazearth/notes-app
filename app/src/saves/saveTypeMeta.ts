import type { GlyphName } from '@/components/Glyph';
import { TYPE_COLORS } from '@/theme/palettes';

/**
 * Visual identity for a knowledge type — the Library equivalent of
 * `spaceMeta.ts`. `knowledge_type` is a free-text column the server's registry
 * just writes (`KnowledgeTypeRegistry`), so a new type is data, not a schema
 * change; the fallback below is what keeps that safe rather than a crash.
 */

export interface SaveTypeMeta {
  type: string;
  /** Plural, for section headers and filter chips. */
  label: string;
  glyph: GlyphName;
  color: string;
  /**
   * Whether this type gets the Saved/Planned/Started/Done progress control.
   * Per CLAUDE.md's "completion must be user-owned" principle: shared only
   * across the Acts that genuinely have a completion state (workout, recipe,
   * itinerary) — never forced onto a save that has nothing to complete
   * (articles, products, reference saves get no ring). Defaults to `false`.
   */
  hasProgress?: boolean;
}

/** The 14 types `KnowledgeTypeRegistry` ships today, in its registration order. */
const KNOWLEDGE_TYPES: SaveTypeMeta[] = [
  { type: 'recipe', label: 'Recipes', glyph: 'utensils', color: TYPE_COLORS.recipe, hasProgress: true },
  { type: 'movie', label: 'Watchlist', glyph: 'film', color: TYPE_COLORS.movie },
  { type: 'place', label: 'Places', glyph: 'mapPin', color: TYPE_COLORS.place },
  { type: 'article', label: 'Articles', glyph: 'fileText', color: TYPE_COLORS.article },
  { type: 'product', label: 'Products', glyph: 'tag', color: TYPE_COLORS.product },
  { type: 'book', label: 'Books', glyph: 'book', color: TYPE_COLORS.book },
  { type: 'workout', label: 'Workouts', glyph: 'activity', color: TYPE_COLORS.workout, hasProgress: true },
  { type: 'recommendation_list', label: 'Recommendations', glyph: 'list', color: TYPE_COLORS.recommendation_list },
  { type: 'checklist', label: 'Checklists', glyph: 'checkSquare', color: TYPE_COLORS.checklist },
  { type: 'itinerary', label: 'Itineraries', glyph: 'compass', color: TYPE_COLORS.itinerary, hasProgress: true },
  { type: 'course', label: 'Courses', glyph: 'graduationCap', color: TYPE_COLORS.course },
  { type: 'github_repo', label: 'Repos', glyph: 'code', color: TYPE_COLORS.github_repo },
  { type: 'other', label: 'Notes', glyph: 'textNote', color: TYPE_COLORS.other },
  { type: 'unusable', label: 'Unusable', glyph: 'close', color: TYPE_COLORS.unusable },
];

const BY_TYPE: Record<string, SaveTypeMeta> = Object.fromEntries(
  KNOWLEDGE_TYPES.map((t) => [t.type, t]),
);

/**
 * Icon, colour and label for a knowledge type. Unknown types (the registry is
 * a data change, so this must never throw) fall back to a generic layered-tile
 * glyph in `other`'s colour, with the raw type title-cased as its label.
 */
export function saveTypeMeta(type: string): SaveTypeMeta {
  return (
    BY_TYPE[type] ?? {
      type,
      label: type.charAt(0).toUpperCase() + type.slice(1),
      glyph: 'layers',
      color: TYPE_COLORS.other,
    }
  );
}

const plural = (singular: string, pluralForm?: string) => (n: number) =>
  n === 1 ? singular : (pluralForm ?? `${singular}s`);

/**
 * What one *save* of a type is called, singular and plural — "1 book",
 * "3 recipes" — for the flat `GroupService`/`@/groups/tree` screens
 * (`GroupDetailScreen`, the Home "AI groups" tiles).
 *
 * A different vocabulary from `collectionTypeMeta`'s `entityNoun`: that one
 * names the merged *entities inside* an entity-bearing type's collection
 * ("14 places" within one itinerary), this names the *saves themselves* when
 * a type has no collection to merge into (or produced none) and is still
 * shown as a plain folder of saves.
 */
const ITEM_NOUNS: Record<string, (count: number) => string> = {
  recipe: plural('recipe'),
  movie: plural('title'),
  place: plural('place'),
  restaurant: plural('place'),
  article: plural('article'),
  product: plural('product'),
  book: plural('book'),
  workout: plural('workout'),
  recommendation_list: plural('list'),
  checklist: plural('checklist'),
  itinerary: plural('itinerary', 'itineraries'),
  course: plural('course'),
  github_repo: plural('repo'),
  other: plural('note'),
};
const ITEM_NOUN_FALLBACK = plural('item');

export function groupItemNoun(type: string, count: number): string {
  return (ITEM_NOUNS[type] ?? ITEM_NOUN_FALLBACK)(count);
}
