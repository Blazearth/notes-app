import type { SaveResponse } from '@/api/types';

/** A field rendered as its own block: either one value or a list of them. */
export interface DetailField {
  label: string;
  /** Exactly one of these is set. */
  text?: string;
  items?: string[];
  /** Steps render numbered; ingredients and tags render as chips. */
  style: 'text' | 'chips' | 'steps';
}

export interface SaveDetailModel {
  title: string;
  /** The single line under the title — type, year, cuisine, that sort of thing. */
  meta?: string;
  /** The one paragraph worth reading first, when the type has one. */
  lede?: string;
  fields: DetailField[];
}

const UNCLEAR = '[unclear]';

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== UNCLEAR ? trimmed : null;
}

function cleanList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => clean(v))
    .filter((v): v is string => v !== null);
}

function join(parts: Array<string | null>): string | undefined {
  const kept = parts.filter((p): p is string => !!p);
  return kept.length ? kept.join(' · ') : undefined;
}

/** `dietaryNotes` → `Dietary notes`. Mirrors `EmbeddingProfile.label` server-side. */
function humanise(key: string): string {
  const spaced = key.replace(/(?<=[a-z0-9])(?=[A-Z])/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

function textField(label: string, value: unknown): DetailField | null {
  const text = clean(value);
  return text ? { label, text, style: 'text' } : null;
}

function listField(
  label: string,
  value: unknown,
  style: 'chips' | 'steps' = 'chips',
): DetailField | null {
  const items = cleanList(value);
  return items.length ? { label, items, style } : null;
}

function compact(fields: Array<DetailField | null>): DetailField[] {
  return fields.filter((f): f is DetailField => f !== null);
}

/**
 * Fields the detail view must never render as content — they are either shown
 * elsewhere (the title, the meta line) or they are bookkeeping.
 */
const HANDLED_ELSEWHERE: Record<string, ReadonlySet<string>> = {
  recipe: new Set(['title', 'servings', 'prepTime', 'cookTime', 'cuisine', 'ingredients', 'steps', 'dietaryNotes']),
  movie: new Set(['title', 'year', 'director', 'genre', 'rating', 'synopsis', 'whereTo']),
  place: new Set(['name', 'type', 'address', 'cuisine', 'priceRange', 'highlights', 'rating']),
  other: new Set(['title', 'summary', 'tags']),
  unusable: new Set(['reason']),
};

/**
 * Anything the model returned that the bespoke layout did not claim.
 *
 * This is what stops a new knowledge type — or a new field on an existing one —
 * from silently vanishing from the UI. The registry is a *data* change
 * server-side by design (CLAUDE.md), so the client must not require a matching
 * code change to show what it produces; it just shows the extra fields
 * generically until someone gives them a nicer home.
 */
function leftovers(data: Record<string, unknown>, knowledgeType: string): DetailField[] {
  const claimed = HANDLED_ELSEWHERE[knowledgeType] ?? new Set<string>();
  return compact(
    Object.entries(data)
      .filter(([key]) => !claimed.has(key))
      .map(([key, value]) =>
        Array.isArray(value) ? listField(humanise(key), value) : textField(humanise(key), value),
      ),
  );
}

/**
 * The full-page view of a save, as opposed to `buildCardModel`'s one-row
 * summary — same source data, different question. The card asks "what is this,
 * at a glance"; this asks "show me everything the model found".
 *
 * Returns `null` when there is nothing structured to show, which the screen
 * renders as a status page rather than an empty one: a save that is still
 * processing or that failed is a legitimate thing to open, and it must explain
 * itself instead of looking broken.
 */
export function buildDetailModel(save: SaveResponse): SaveDetailModel | null {
  if (save.status !== 'ready' || !save.knowledgeType || !save.structuredData) return null;
  const d = save.structuredData;
  const type = save.knowledgeType;

  switch (type) {
    case 'recipe': {
      const title = clean(d.title);
      if (!title) return null;
      const servings = clean(d.servings);
      return {
        title,
        meta: join([
          servings ? `${servings} servings` : null,
          clean(d.prepTime) ? `${clean(d.prepTime)} prep` : null,
          clean(d.cookTime) ? `${clean(d.cookTime)} cook` : null,
          clean(d.cuisine),
        ]),
        fields: [
          ...compact([
            listField('Ingredients', d.ingredients),
            listField('Steps', d.steps, 'steps'),
            listField('Dietary notes', d.dietaryNotes),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'movie': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        meta: join([clean(d.year), clean(d.director), cleanList(d.genre).join(', ') || null]),
        lede: clean(d.synopsis) ?? undefined,
        fields: [
          ...compact([
            listField('Genre', d.genre),
            textField('Rating', d.rating),
            textField('Where to watch', d.whereTo),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'place': {
      // The registry names this `name`, not `title`.
      const title = clean(d.name);
      if (!title) return null;
      return {
        title,
        meta: join([clean(d.type), clean(d.cuisine), clean(d.priceRange)]),
        fields: [
          ...compact([
            textField('Address', d.address),
            listField('Highlights', d.highlights),
            textField('Rating', d.rating),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'other': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        lede: clean(d.summary) ?? undefined,
        fields: [...compact([listField('Tags', d.tags)]), ...leftovers(d, type)],
      };
    }

    default: {
      // A knowledge type the registry gained without this file changing. Show
      // it generically rather than nothing — see `leftovers`.
      const titleKey = clean(d.title) ? 'title' : clean(d.name) ? 'name' : null;
      if (!titleKey) return null;
      return {
        title: clean(d[titleKey]) as string,
        // Whichever key supplied the heading must not also render as a field
        // below it. Only the bespoke branches above have a static claim list,
        // so for an unknown type the claim has to be worked out here.
        fields: leftovers(d, type).filter((f) => f.label !== humanise(titleKey)),
      };
    }
  }
}
