import type { SaveResponse } from '@/api/types';

export type CardKind =
  | 'recipe'
  | 'movie'
  | 'place'
  | 'article'
  | 'product'
  | 'book'
  | 'workout'
  | 'recommendation_list'
  | 'other';

export interface SaveCardModel {
  kind: CardKind;
  title: string;
  meta?: string;
  summary?: string;
  chips?: string[];
  chipsOverflow?: number;
}

/** The sentinel Gemini uses for "genuinely not present" (KnowledgeTypeRegistry). */
const UNCLEAR = '[unclear]';

function clean(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== UNCLEAR ? trimmed : null;
}

function cleanList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((v): v is string => typeof v === 'string' && v.trim().length > 0);
}

function joinMeta(parts: Array<string | null>): string | undefined {
  const kept = parts.filter((p): p is string => !!p);
  return kept.length ? kept.join(' · ') : undefined;
}

function withOverflow(items: string[], max: number): { chips: string[]; overflow: number } {
  return { chips: items.slice(0, max), overflow: Math.max(0, items.length - max) };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * A card chip per ingredient, whichever shape the save holds: legacy flat
 * strings ("250g mascarpone") or the registry's `{name, quantity, note}`
 * objects from 2026-08-07 on. Old saves are never reprocessed, so both shapes
 * are permanent. The chip keeps just the name for objects — a card is a
 * glance, and "mascarpone" glances better than "250g mascarpone, softened".
 */
function ingredientChips(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => (isRecord(v) ? clean(v.name) : clean(v)))
    .filter((v): v is string => v !== null);
}

/** Exercise names for the workout card; legacy saves without them fall back to equipment. */
function exerciseChips(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => (isRecord(v) ? clean(v.name) : null))
    .filter((v): v is string => v !== null);
}

/**
 * Turns a save's untyped `structuredData` into a layout `SaveCard` can render
 * without every screen re-deriving field names — the mirror, client-side, of
 * `KnowledgeTypeRegistry` on the server. Returns `null` for anything still
 * processing, `unusable`, or a knowledge type without a bespoke layout yet;
 * `SaveCard` falls back to the flat `ListRow` in that case.
 */
export function buildCardModel(save: SaveResponse): SaveCardModel | null {
  if (save.status !== 'ready' || !save.knowledgeType || !save.structuredData) return null;
  const d = save.structuredData;

  switch (save.knowledgeType) {
    case 'recipe': {
      const title = clean(d.title);
      if (!title) return null;
      const servings = clean(d.servings);
      const { chips, overflow } = withOverflow(ingredientChips(d.ingredients), 4);
      return {
        kind: 'recipe',
        title,
        meta: joinMeta([servings ? `${servings} servings` : null, clean(d.prepTime), clean(d.cookTime)]),
        chips,
        chipsOverflow: overflow,
      };
    }

    case 'movie': {
      const title = clean(d.title);
      if (!title) return null;
      const genre = cleanList(d.genre).join(', ');
      return {
        kind: 'movie',
        title,
        meta: joinMeta([clean(d.year), genre || null]),
        summary: clean(d.synopsis) ?? undefined,
      };
    }

    case 'place': {
      // The registry names this field `name`, not `title`.
      const title = clean(d.name);
      if (!title) return null;
      const { chips, overflow } = withOverflow(cleanList(d.highlights), 3);
      return {
        kind: 'place',
        title,
        meta: joinMeta([clean(d.type), clean(d.cuisine), clean(d.priceRange)]),
        chips,
        chipsOverflow: overflow,
      };
    }

    case 'article': {
      const title = clean(d.title);
      if (!title) return null;
      const { chips, overflow } = withOverflow(cleanList(d.tags), 3);
      return {
        kind: 'article',
        title,
        meta: joinMeta([clean(d.category)]),
        summary: clean(d.summary) ?? undefined,
        chips,
        chipsOverflow: overflow,
      };
    }

    case 'product': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        kind: 'product',
        title,
        meta: joinMeta([clean(d.category), clean(d.price), clean(d.whereTo)]),
        summary: clean(d.summary) ?? undefined,
      };
    }

    case 'book': {
      const title = clean(d.title);
      if (!title) return null;
      const { chips, overflow } = withOverflow(cleanList(d.genre), 3);
      return {
        kind: 'book',
        title,
        meta: joinMeta([clean(d.author), clean(d.rating)]),
        summary: clean(d.summary) ?? undefined,
        chips,
        chipsOverflow: overflow,
      };
    }

    case 'workout': {
      const title = clean(d.title);
      if (!title) return null;
      const exercises = exerciseChips(d.exercises);
      const { chips, overflow } = withOverflow(exercises.length ? exercises : cleanList(d.equipment), 4);
      return {
        kind: 'workout',
        title,
        meta: joinMeta([clean(d.duration), clean(d.category)]),
        summary: clean(d.summary) ?? undefined,
        chips,
        chipsOverflow: overflow,
      };
    }

    case 'other': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        kind: 'other',
        title,
        meta: joinMeta([clean(d.category)]),
        summary: clean(d.summary) ?? undefined,
      };
    }

    // The flagship Phase 3 type — a "top 10" video is a list to work
    // through, not a single title. Chips are item names, same glance-value
    // as a recipe's ingredient chips.
    case 'recommendation_list': {
      const title = clean(d.title);
      if (!title) return null;
      const itemNames = Array.isArray(d.items)
        ? d.items.map((v) => (isRecord(v) ? clean(v.name) : null)).filter((v): v is string => v !== null)
        : [];
      const { chips, overflow } = withOverflow(itemNames, 4);
      return {
        kind: 'recommendation_list',
        title,
        meta: joinMeta([clean(d.medium), itemNames.length ? `${itemNames.length} items` : null]),
        summary: clean(d.summary) ?? undefined,
        chips,
        chipsOverflow: overflow,
      };
    }

    default:
      return null;
  }
}
