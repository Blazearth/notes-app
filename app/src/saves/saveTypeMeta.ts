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
}

/** The 9 types `KnowledgeTypeRegistry` ships today, in its registration order. */
const KNOWLEDGE_TYPES: SaveTypeMeta[] = [
  { type: 'recipe', label: 'Recipes', glyph: 'utensils', color: TYPE_COLORS.recipe },
  { type: 'movie', label: 'Watchlist', glyph: 'film', color: TYPE_COLORS.movie },
  { type: 'place', label: 'Places', glyph: 'mapPin', color: TYPE_COLORS.place },
  { type: 'article', label: 'Articles', glyph: 'fileText', color: TYPE_COLORS.article },
  { type: 'product', label: 'Products', glyph: 'tag', color: TYPE_COLORS.product },
  { type: 'book', label: 'Books', glyph: 'book', color: TYPE_COLORS.book },
  { type: 'workout', label: 'Workouts', glyph: 'activity', color: TYPE_COLORS.workout },
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
