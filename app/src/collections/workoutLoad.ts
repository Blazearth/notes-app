/**
 * §5.2 local compute: total sets and an estimated session length from a set
 * of exercises' own `sets`/`rest` values — arithmetic on what the model
 * already extracted, never a model call and never invented content.
 *
 * Ported out of `detailModel.ts`'s `workoutLoadField` (a single save's own
 * routine) so the collection layer's per-split summary — the same estimate
 * over a *merged* split's exercises — can share one implementation instead of
 * a second copy that would drift on the first "1m30s" nobody handled. Both
 * callers label the minutes figure "(est.)"; neither ever shows it when the
 * content already stated a duration, which is a caller-side decision this
 * module has no opinion on.
 *
 * Pure: no store, no theme, no React. Executed under node against fixtures.
 */

/**
 * A fixed, undisclosed-to-the-user constant standing in for time-under-
 * tension plus transition between sets — it is not extracted from anything,
 * which is why every result built from it is labelled an estimate rather
 * than presented as a fact.
 */
export const ASSUMED_SECONDS_PER_SET = 40;

export interface SessionLoad {
  totalSets: number;
  countedExercises: number;
  estimatedMinutes: number;
}

function clean(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed.toLowerCase() !== '[unclear]' ? trimmed : null;
}

export function parseLeadingInt(value: unknown): number | null {
  const text = clean(value);
  if (!text) return null;
  const match = text.match(/\d+/);
  return match ? parseInt(match[0], 10) : null;
}

export function parseRestSeconds(value: unknown): number | null {
  const text = clean(value);
  if (!text) return null;
  const match = text.match(/(\d+(?:\.\d+)?)\s*(s|sec|second|m|min|minute)/i);
  if (!match) return null;
  const amount = parseFloat(match[1]);
  return Math.round(match[2].toLowerCase().startsWith('m') ? amount * 60 : amount);
}

/**
 * `null` when nothing in `items` carries a usable `sets` value — the same
 * "don't guess" rule as everywhere else a field might be `[unclear]`.
 */
export function estimateSessionLoad(items: readonly Record<string, unknown>[]): SessionLoad | null {
  let totalSets = 0;
  let estimatedSeconds = 0;
  let countedExercises = 0;
  for (const item of items) {
    const sets = parseLeadingInt(item.sets);
    if (sets === null) continue;
    countedExercises += 1;
    totalSets += sets;
    const rest = parseRestSeconds(item.rest) ?? 0;
    estimatedSeconds += sets * (ASSUMED_SECONDS_PER_SET + rest);
  }
  if (!countedExercises) return null;
  return {
    totalSets,
    countedExercises,
    estimatedMinutes: Math.max(1, Math.round(estimatedSeconds / 60)),
  };
}
