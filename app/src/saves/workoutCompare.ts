/**
 * K5 — the alternative that ships instead of AI synthesis.
 *
 * `docs/knowledge-collections.md`'s K5 section states the constraint plainly:
 * a merged "AI continuously improves your program" from three creators'
 * push days is a *generative* rewrite, not aggregation, and a second Gemini
 * call per view has no budget line in CLAUDE.md's request-budget section —
 * and per that doc's own rule, if the line can't be justified the phase
 * doesn't exist. It doesn't here. What ships instead is exactly what the doc
 * names as the free alternative: side-by-side compare, entirely local
 * compute over fields the one classify call already extracted — the same
 * cost class `workoutLoadField` (`detailModel.ts`, §5.2) already uses for a
 * single workout's own session-length estimate, extended across several.
 *
 * Nothing here is stored, nothing is a model call, and nothing invents a
 * fact a source didn't state — an estimate is carried with its own flag so a
 * caller can label it, the same "(estimated)" contract `detailModel.ts`
 * already applies to a single workout's `estimatedDurationMin`/
 * `estimatedDifficulty`.
 *
 * The shape here is a comparison **table** — attribute rows, one column per
 * workout — rather than a card per workout. A card-per-workout layout reads
 * fine at desktop width but on a ~360-400px phone viewport only one card is
 * ever fully visible, which defeats "side by side" entirely; a table's rows
 * stay legible because only the *columns* need horizontal room, and 2-3
 * short values fit without scrolling.
 */

import type { SaveResponse } from '@/api/types';

export interface WorkoutCompareColumn {
  saveId: string;
  title: string;
  sourceUrl?: string;
  /** Hostname-ish label for the Sources list — never a raw scheme+path dump. */
  sourceLabel: string;
}

export interface WorkoutCompareStatValue {
  text: string;
  estimate: boolean;
}

export interface WorkoutCompareStatRow {
  label: string;
  /** One entry per column, same order as `WorkoutComparison.columns`. */
  values: WorkoutCompareStatValue[];
  /**
   * True when every column has the same underlying value. Drives the
   * emphasize-differences / de-emphasize-agreement styling the table uses —
   * a comparison's whole point is showing what's different.
   */
  allSame: boolean;
}

export interface WorkoutCompareMembershipRow {
  label: string;
  /** One entry per column: does that workout include this muscle group / equipment item. */
  present: boolean[];
  /** True when every column has it — rendered ahead of the partial rows. */
  inAll: boolean;
}

export interface WorkoutComparison {
  columns: WorkoutCompareColumn[];
  stats: WorkoutCompareStatRow[];
  muscleGroups: WorkoutCompareMembershipRow[];
  equipment: WorkoutCompareMembershipRow[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function clean(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== '[unclear]' ? trimmed : null;
}

function cleanList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map(clean).filter((v): v is string => v !== null);
}

function parseLeadingInt(value: unknown): number | null {
  const text = clean(value);
  if (!text) return null;
  const match = text.match(/\d+/);
  return match ? parseInt(match[0], 10) : null;
}

function sourceLabel(sourceUrl: string | undefined): string {
  if (!sourceUrl) return 'Saved note';
  try {
    return new URL(sourceUrl).hostname.replace(/^www\./, '');
  } catch {
    return sourceUrl;
  }
}

interface WorkoutFields {
  saveId: string;
  title: string;
  sourceUrl?: string;
  goal: string | null;
  muscleGroups: string[];
  equipment: string[];
  difficulty: string | null;
  difficultyIsEstimate: boolean;
  durationMin: number | null;
  durationIsEstimate: boolean;
  exerciseCount: number;
}

/** One workout's own extracted fields — `null` if it isn't a usable workout save. */
function workoutFields(save: SaveResponse): WorkoutFields | null {
  if (save.knowledgeType !== 'workout' || !isRecord(save.structuredData)) return null;
  const d = save.structuredData;
  const title = clean(d.title);
  if (!title) return null;

  const statedDuration = parseLeadingInt(d.duration);
  const estimatedDuration = parseLeadingInt(d.estimatedDurationMin);
  const statedDifficulty = clean(d.difficulty);
  const estimatedDifficulty = clean(d.estimatedDifficulty);

  return {
    saveId: save.id,
    title,
    sourceUrl: save.sourceUrl,
    goal: clean(d.goal),
    muscleGroups: cleanList(d.muscleGroups),
    equipment: cleanList(d.equipment),
    difficulty: statedDifficulty ?? estimatedDifficulty,
    difficultyIsEstimate: !statedDifficulty && !!estimatedDifficulty,
    durationMin: statedDuration ?? estimatedDuration,
    durationIsEstimate: statedDuration === null && estimatedDuration !== null,
    exerciseCount: Array.isArray(d.exercises) ? d.exercises.length : 0,
  };
}

/** A stat row from a per-column raw-value + formatter pair. `allSame` compares the raw values, never the formatted text. */
function statRow<T>(
  label: string,
  rows: WorkoutFields[],
  raw: (w: WorkoutFields) => T,
  format: (w: WorkoutFields) => WorkoutCompareStatValue,
): WorkoutCompareStatRow {
  const rawValues = rows.map(raw);
  const allSame = rawValues.every((v) => v !== null && v === rawValues[0]);
  return { label, values: rows.map(format), allSame };
}

/** Union of a per-workout list field, in first-seen order, as membership rows across all columns. */
function membershipRows(rows: WorkoutFields[], pick: (w: WorkoutFields) => string[]): WorkoutCompareMembershipRow[] {
  const lists = rows.map(pick);
  const order: string[] = [];
  for (const list of lists) {
    for (const item of list) if (!order.includes(item)) order.push(item);
  }
  const built = order.map((label) => {
    const present = lists.map((list) => list.includes(label));
    return { label, present, inAll: present.every(Boolean) };
  });
  // Shared items first — that's the "in common" answer a comparison exists to give.
  return built.sort((a, b) => Number(b.inAll) - Number(a.inAll));
}

/**
 * Pure: no network, no AI, no stored state — every field is either read
 * directly from `structuredData` or arithmetic over it, the same cost class
 * as `workoutLoadField`. Rows that aren't usable workout saves are dropped
 * rather than shown blank.
 */
export function compareWorkouts(saves: SaveResponse[]): WorkoutComparison {
  const rows = saves.map(workoutFields).filter((r): r is WorkoutFields => r !== null);

  const columns: WorkoutCompareColumn[] = rows.map((r) => ({
    saveId: r.saveId,
    title: r.title,
    sourceUrl: r.sourceUrl,
    sourceLabel: sourceLabel(r.sourceUrl),
  }));

  const stats: WorkoutCompareStatRow[] = [
    statRow(
      'Goal',
      rows,
      (w) => w.goal,
      (w) => ({ text: w.goal ?? '—', estimate: false }),
    ),
    statRow(
      'Duration',
      rows,
      (w) => w.durationMin,
      (w) => ({
        text: w.durationMin !== null ? `${w.durationMin} min` : '—',
        estimate: w.durationIsEstimate,
      }),
    ),
    statRow(
      'Difficulty',
      rows,
      (w) => w.difficulty,
      (w) => ({ text: w.difficulty ?? '—', estimate: w.difficultyIsEstimate }),
    ),
    statRow(
      'Exercises',
      rows,
      (w) => w.exerciseCount,
      (w) => ({ text: String(w.exerciseCount), estimate: false }),
    ),
  ];

  return {
    columns,
    stats,
    muscleGroups: membershipRows(rows, (w) => w.muscleGroups),
    equipment: membershipRows(rows, (w) => w.equipment),
  };
}
