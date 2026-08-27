/**
 * What a merged entity actually says — the rolled-up fields, rendered per
 * type.
 *
 * K6 left a gap it named: a workout exercise's sheet read "No details yet"
 * while its `sets`, `reps`, `rest` and `cues` sat rolled up in `fields`,
 * unrendered. The generic renderer could not show them because it only ever
 * knew about `kind` and `year` — fields that happen to exist on a
 * recommendation and on nothing else.
 *
 * The fix is per-type field *order and labelling*, plus a generic pass for
 * anything not named — so a new registry field on any wired type shows up
 * immediately (labelled from its own name) rather than disappearing, and a
 * type nobody anticipated still renders everything it has. That is the same
 * bargain `detailModel.ts` strikes for a save.
 *
 * Pure: no store, no theme, no React. Executed under node against fixtures.
 */

import { titleCase } from '@/knowledge/facets';

export interface EntityField {
  label: string;
  /** Already flattened for display — a list is joined, a scalar trimmed. */
  value: string;
  /** True when the source was a list, so the screen can render it as chips. */
  isList: boolean;
}

/** Never rendered: machine identity and image URLs, which are plumbing. */
const HIDDEN_FIELDS = new Set(['posterUrl', 'tmdbId', 'imageUrl', 'thumbnailUrl']);

/**
 * The fields worth leading with, per type, in the order they should read.
 * Anything not listed still renders, after these, in whatever order the
 * rollup produced.
 */
const LEAD_FIELDS: Record<string, string[]> = {
  workout: ['sets', 'reps', 'rest', 'tempo', 'cues', 'alternatives'],
  itinerary: ['day', 'area', 'timeNeeded', 'cost', 'tips'],
  recommendation_list: ['year', 'rank', 'platform', 'genre', 'reason'],
  checklist: ['detail'],
};

/** Labels that would read badly if derived from the field name alone. */
const FIELD_LABELS: Record<string, string> = {
  timeNeeded: 'Time needed',
  platform: 'Where to watch',
  cues: 'Technique',
  alternatives: 'Instead of this',
  reason: 'Why',
  detail: 'Note',
  muscleGroups: 'Muscles',
  bestSeason: 'Best season',
  durationDays: 'Days',
};

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? titleCase(field.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase());
}

function usable(value: unknown): boolean {
  if (typeof value === 'number') return true;
  if (typeof value !== 'string') return false;
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.toLowerCase() !== '[unclear]';
}

function scalarText(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return usable(trimmed) ? trimmed : null;
}

function listText(value: unknown[]): string | null {
  const parts = value.map(scalarText).filter((v): v is string => !!v);
  return parts.length > 0 ? parts.join(' · ') : null;
}

function toField(field: string, raw: unknown): EntityField | null {
  if (Array.isArray(raw)) {
    const value = listText(raw);
    return value ? { label: fieldLabel(field), value, isList: true } : null;
  }
  const value = scalarText(raw);
  return value ? { label: fieldLabel(field), value, isList: false } : null;
}

/**
 * Everything the merged entity has to say, lead fields first.
 *
 * `reason` is deliberately *excluded* for `recommendation_list` even though
 * it is listed in LEAD_FIELDS — see {@link entityDetailFields}'s
 * `excludeFields`. The rolled-up `reason` is one source's, chosen
 * arbitrarily by the scalar rollup, and the sheet already shows every
 * source's own reason attributed to it. Showing a headline "Why" above that
 * would present one recommender's words as the collective view, which is
 * exactly the blending K1 refused.
 */
export function entityDetailFields(
  type: string,
  fields: Record<string, unknown>,
  excludeFields: readonly string[] = [],
): EntityField[] {
  const skip = new Set([...HIDDEN_FIELDS, ...excludeFields]);
  const lead = (LEAD_FIELDS[type] ?? []).filter((f) => !skip.has(f));
  const rest = Object.keys(fields).filter((f) => !skip.has(f) && !lead.includes(f));

  const out: EntityField[] = [];
  for (const field of [...lead, ...rest]) {
    const built = toField(field, fields[field]);
    if (built) out.push(built);
  }
  return out;
}

/** Fields whose value is already carried by the row's own layout, per type. */
const ROW_META: Record<string, (fields: Record<string, unknown>) => string | null> = {
  // "4 × 6-8 · rest 2 min" — the prescription, which is what an exercise is.
  workout: (f) => {
    const sets = scalarText(f.sets);
    const reps = scalarText(f.reps);
    const rest = scalarText(f.rest);
    const prescription = sets && reps ? `${sets} × ${reps}` : (sets ?? reps);
    return [prescription, rest ? `rest ${rest}` : null].filter(Boolean).join(' · ') || null;
  },
  // "4 days" — how long to allow. `day` is deliberately left off: the
  // collection screen groups itinerary places by day, so it's already the
  // section heading above this row and repeating it here would say it twice.
  itinerary: (f) => scalarText(f.timeNeeded),
  checklist: (f) => scalarText(f.detail),
};

/**
 * The one compact line under an entity's name in a list.
 *
 * Per type, because "anime · 2024" is right for a title and says nothing
 * about an exercise. `kind` is only part of it where kind actually varies —
 * see `collectionTypeMeta.showsKind`, which the caller applies.
 */
export function entityMetaLine(
  type: string,
  kind: string | null,
  fields: Record<string, unknown>,
): string | null {
  const builder = ROW_META[type];
  if (builder) {
    const built = builder(fields);
    return [kind, built].filter(Boolean).join(' · ') || null;
  }
  // The default, and what `recommendation_list` wants: what it is, and when.
  return [kind, scalarText(fields.year)].filter(Boolean).join(' · ') || null;
}
