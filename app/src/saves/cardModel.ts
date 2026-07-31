import type { SaveResponse } from '@/api/types';

export type CardKind = 'recipe' | 'movie' | 'place' | 'other';

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
      const { chips, overflow } = withOverflow(cleanList(d.ingredients), 4);
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

    case 'other': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        kind: 'other',
        title,
        summary: clean(d.summary) ?? undefined,
      };
    }

    default:
      return null;
  }
}
