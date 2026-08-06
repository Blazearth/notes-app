import type { SaveResponse } from '@/api/types';

/** One row inside a {@link DetailObject} — a labelled value or chip list. */
export interface DetailObjectRow {
  label: string;
  text?: string;
  items?: string[];
}

/**
 * One entry of a nested object array — an exercise, a structured ingredient.
 * Renders as a sub-card: title, a compact meta line, then labelled rows.
 */
export interface DetailObject {
  title?: string;
  meta?: string;
  rows: DetailObjectRow[];
}

/** A field rendered as its own block: one value, a list of them, or objects. */
export interface DetailField {
  label: string;
  /** Exactly one of these is set. */
  text?: string;
  items?: string[];
  objects?: DetailObject[];
  /** Steps render numbered; ingredients and tags render as chips. */
  style: 'text' | 'chips' | 'steps' | 'objects';
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Ingredients exist in two shapes forever: saves classified before 2026-08-07
 * hold flat strings ("250g mascarpone"), newer ones hold
 * `{name, quantity, note}` objects — there is no reprocess path, so the old
 * shape never ages out. Both fold to the same display line.
 */
function ingredientText(value: unknown): string | null {
  if (isRecord(value)) {
    const name = clean(value.name);
    if (!name) return null;
    const quantity = clean(value.quantity);
    const note = clean(value.note);
    return `${quantity ? `${quantity} ` : ''}${name}${note ? `, ${note}` : ''}`;
  }
  return clean(value);
}

function ingredientChips(value: unknown): DetailField | null {
  if (!Array.isArray(value)) return null;
  const items = value.map(ingredientText).filter((v): v is string => v !== null);
  return items.length ? { label: 'Ingredients', items, style: 'chips' } : null;
}

/**
 * The bespoke exercises layout: name as the card title, the prescription
 * (sets × reps · rest · tempo) as one compact meta line, cues and
 * alternatives as chip rows.
 */
function exercisesField(value: unknown): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  for (const raw of value) {
    if (!isRecord(raw)) continue;
    const name = clean(raw.name);
    if (!name) continue;
    const sets = clean(raw.sets);
    const reps = clean(raw.reps);
    const rows: DetailObjectRow[] = [];
    const cues = cleanList(raw.cues);
    if (cues.length) rows.push({ label: 'Cues', items: cues });
    const alternatives = cleanList(raw.alternatives);
    if (alternatives.length) rows.push({ label: 'Alternatives', items: alternatives });
    objects.push({
      title: name,
      meta: join([
        sets && reps ? `${sets} × ${reps}` : sets ? `${sets} sets` : reps,
        clean(raw.rest) ? `rest ${clean(raw.rest)}` : null,
        clean(raw.tempo) ? `tempo ${clean(raw.tempo)}` : null,
      ]),
      rows,
    });
  }
  return objects.length ? { label: 'Exercises', objects, style: 'objects' } : null;
}

/**
 * The generic shape for a nested object array the client has no bespoke
 * layout for — the same promise `leftovers` makes for flat fields, extended
 * one level down: a new objectArray field in the registry renders as
 * sub-cards instead of silently vanishing.
 */
function objectListField(label: string, value: unknown): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  for (const raw of value) {
    if (!isRecord(raw)) continue;
    const titleKey = clean(raw.name) ? 'name' : clean(raw.title) ? 'title' : null;
    const rows: DetailObjectRow[] = [];
    for (const [key, v] of Object.entries(raw)) {
      if (key === titleKey) continue;
      if (Array.isArray(v)) {
        const items = cleanList(v);
        if (items.length) rows.push({ label: humanise(key), items });
      } else {
        const text = clean(v);
        if (text) rows.push({ label: humanise(key), text });
      }
    }
    if (titleKey || rows.length) {
      objects.push({ title: titleKey ? (clean(raw[titleKey]) as string) : undefined, rows });
    }
  }
  return objects.length ? { label, objects, style: 'objects' } : null;
}

/**
 * Fields the detail view must never render as content — they are either shown
 * elsewhere (the title, the meta line) or they are bookkeeping.
 */
const HANDLED_ELSEWHERE: Record<string, ReadonlySet<string>> = {
  recipe: new Set(['title', 'servings', 'prepTime', 'cookTime', 'cuisine', 'ingredients', 'steps', 'dietaryNotes']),
  movie: new Set(['title', 'year', 'director', 'genre', 'rating', 'synopsis', 'whereTo']),
  place: new Set(['name', 'type', 'address', 'cuisine', 'priceRange', 'highlights', 'rating']),
  workout: new Set([
    'title', 'summary', 'category', 'goal', 'muscleGroups', 'duration', 'difficulty',
    'equipment', 'warmup', 'exercises', 'cooldown', 'progression', 'warnings',
  ]),
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
        Array.isArray(value) && value.some(isRecord)
          ? objectListField(humanise(key), value)
          : Array.isArray(value)
            ? listField(humanise(key), value)
            : textField(humanise(key), value),
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
            ingredientChips(d.ingredients),
            listField('Steps', d.steps, 'steps'),
            listField('Dietary notes', d.dietaryNotes),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'workout': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        meta: join([clean(d.duration), clean(d.category), clean(d.goal), clean(d.difficulty)]),
        lede: clean(d.summary) ?? undefined,
        fields: [
          ...compact([
            listField('Muscle groups', d.muscleGroups),
            listField('Equipment', d.equipment),
            listField('Warm-up', d.warmup, 'steps'),
            exercisesField(d.exercises),
            listField('Cool-down', d.cooldown, 'steps'),
            textField('Progression', d.progression),
            listField('Watch out', d.warnings),
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
