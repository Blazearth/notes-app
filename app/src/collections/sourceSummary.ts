/**
 * What one *source* contributed to a collection — the answer to "why are
 * these seven places together?"
 *
 * A collection screen shows merged entities, which is the right default and
 * loses one thing: the shape each individual save had. "14 Days in Japan"
 * is a route, in order, with day ranges; the merged place list is a set. Both
 * are true and the route is what a person recognises, so a destination's
 * overview leads with it.
 *
 * Deliberately reads the save's own `structuredData`, not the merged
 * entities — this is the un-merged view by definition. Pure, so it runs
 * standalone under node against fixtures.
 */

export interface SourceSummary {
  saveId: string;
  title: string;
  /** "14 days · 4 places", or null when the save states nothing worth a line. */
  meta: string | null;
  /** "Tokyo → Mount Fuji → Kyoto", or the first few items — the shape of this source. */
  outline: string | null;
  /** How many of this source's items are in the node being viewed. */
  itemCount: number;
}

interface SourceFacts {
  id: string;
  knowledgeType?: string;
  structuredData?: Record<string, unknown> | null;
}

/** How many outline steps to show before the ellipsis — enough to recognise a route by. */
const OUTLINE_LIMIT = 4;

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed.toLowerCase() !== '[unclear]' ? trimmed : null;
}

function items(save: SourceFacts, field: string): Record<string, unknown>[] {
  const raw = save.structuredData?.[field];
  if (!Array.isArray(raw)) return [];
  return raw.filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v));
}

function names(list: Record<string, unknown>[], nameField: string): string[] {
  return list.map((item) => clean(item[nameField])).filter((v): v is string => !!v);
}

/** Which field holds a type's items and what its name field is — mirrors the merge's ITEM_SHAPES. */
const SHAPES: Record<string, { itemsField: string; nameField: string }> = {
  itinerary: { itemsField: 'places', nameField: 'name' },
  recommendation_list: { itemsField: 'items', nameField: 'name' },
  checklist: { itemsField: 'items', nameField: 'text' },
  workout: { itemsField: 'exercises', nameField: 'name' },
};

/** Separators that read the way each type's list actually works. */
const JOINERS: Record<string, string> = {
  // A trip is a sequence — the arrow is load-bearing information, not decoration.
  itinerary: ' → ',
  workout: ' → ',
  recommendation_list: ' · ',
  checklist: ' · ',
};

/** The per-type "what is this source" line. */
const META: Record<string, (data: Record<string, unknown>, count: number) => (string | null)[]> = {
  itinerary: (d, count) => [
    clean(d.durationDays) ? `${clean(d.durationDays)} days` : null,
    `${count} ${count === 1 ? 'place' : 'places'}`,
  ],
  workout: (d, count) => [
    clean(d.duration) ?? (clean(d.estimatedDurationMin) ? `~${clean(d.estimatedDurationMin)} min` : null),
    `${count} ${count === 1 ? 'exercise' : 'exercises'}`,
    clean(d.difficulty) ?? clean(d.estimatedDifficulty),
  ],
  recommendation_list: (d, count) => [
    `${count} ${count === 1 ? 'title' : 'titles'}`,
    clean(d.orderMatters) === 'yes' ? 'in order' : null,
  ],
  checklist: (_d, count) => [`${count} ${count === 1 ? 'task' : 'tasks'}`],
};

/**
 * One source save, summarised for a collection screen.
 *
 * `title` falls back to the type's own name field before the generic one,
 * because a save's title is the one field this repo has repeatedly got wrong
 * by assuming `title` — see `place`'s `name`.
 */
export function sourceSummary(save: SourceFacts): SourceSummary {
  const type = save.knowledgeType ?? '';
  const data = save.structuredData ?? {};
  const shape = SHAPES[type];

  const list = shape ? items(save, shape.itemsField) : [];
  const itemNames = shape ? names(list, shape.nameField) : [];

  const shown = itemNames.slice(0, OUTLINE_LIMIT);
  const outline =
    shown.length > 0
      ? shown.join(JOINERS[type] ?? ' · ') + (itemNames.length > shown.length ? '…' : '')
      : null;

  const metaParts = META[type]?.(data, itemNames.length) ?? [];
  const meta = metaParts.filter(Boolean).join(' · ') || null;

  return {
    saveId: save.id,
    title: clean(data.title) ?? clean(data.name) ?? 'Untitled',
    meta,
    outline,
    itemCount: itemNames.length,
  };
}

export function sourceSummaries(saves: SourceFacts[]): SourceSummary[] {
  return saves.map(sourceSummary);
}
