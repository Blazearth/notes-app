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
 */

import type { SaveResponse } from '@/api/types';

export interface WorkoutCompareRow {
  saveId: string;
  title: string;
  goal: string | null;
  muscleGroups: string[];
  equipment: string[];
  difficulty: string | null;
  difficultyIsEstimate: boolean;
  durationMin: number | null;
  durationIsEstimate: boolean;
  exerciseCount: number;
  exerciseNames: string[];
}

export interface WorkoutComparison {
  rows: WorkoutCompareRow[];
  /** Muscle groups every compared workout trains — empty unless there are at least two rows. */
  commonMuscleGroups: string[];
  /** Equipment every compared workout needs. */
  commonEquipment: string[];
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

/** One workout's own comparison row — `null` if it isn't a usable workout save. */
function compareRow(save: SaveResponse): WorkoutCompareRow | null {
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
    goal: clean(d.goal),
    muscleGroups: cleanList(d.muscleGroups),
    equipment: cleanList(d.equipment),
    difficulty: statedDifficulty ?? estimatedDifficulty,
    difficultyIsEstimate: !statedDifficulty && !!estimatedDifficulty,
    durationMin: statedDuration ?? estimatedDuration,
    durationIsEstimate: statedDuration === null && estimatedDuration !== null,
    exerciseCount: Array.isArray(d.exercises) ? d.exercises.length : 0,
    exerciseNames: Array.isArray(d.exercises)
      ? d.exercises.map((e) => (isRecord(e) ? clean(e.name) : null)).filter((v): v is string => v !== null)
      : [],
  };
}

function intersection(lists: string[][]): string[] {
  if (lists.length < 2) return [];
  const [first, ...rest] = lists;
  return first.filter((item) => rest.every((list) => list.includes(item)));
}

/**
 * Pure: no network, no AI, no stored state — every field is either read
 * directly from `structuredData` or arithmetic over it, the same cost class
 * as `workoutLoadField`. Rows that aren't usable workout saves are dropped
 * rather than shown blank.
 */
export function compareWorkouts(saves: SaveResponse[]): WorkoutComparison {
  const rows = saves.map(compareRow).filter((r): r is WorkoutCompareRow => r !== null);
  return {
    rows,
    commonMuscleGroups: intersection(rows.map((r) => r.muscleGroups)),
    commonEquipment: intersection(rows.map((r) => r.equipment)),
  };
}
