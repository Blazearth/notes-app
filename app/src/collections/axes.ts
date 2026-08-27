/**
 * How a collection subdivides — the axis chain, per knowledge type.
 *
 * Hand-kept port of `CollectionAxes.java` (`api/.../collection/`), the same
 * arrangement `CollectionService`/`merge.ts` already have; the Java file is
 * the source of truth if the two drift, and `CollectionServiceTest.java`
 * pins its behaviour.
 *
 * The three generalisations over K1's single save-level facet, and why each
 * one is here:
 *
 * - **A chain, not a field.** `recommendation_list` splits by item kind and
 *   then by genre, so Recommendations → Anime → Romance is a path rather
 *   than a special case.
 * - **Entity-level as well as save-level.** A `save` axis reads the
 *   containing save's `structuredData` (every item in that save inherits the
 *   value); an `entity` axis reads the merged entity, so a title recommended
 *   by two saves is filed once, by what it *is*.
 * - **Multi-valued.** `genre` and `muscleGroups` are arrays, so an entity
 *   legitimately belongs to several buckets at one level. Counts stay honest
 *   because a node counts *distinct* entities across its subtree rather than
 *   summing children.
 */

import { titleCase } from '@/knowledge/facets';

/** Where an axis reads its value from. */
export type AxisSource = 'save' | 'entity';

export interface CollectionAxis {
  from: AxisSource;
  /** Field name on the save's `structuredData`, or on the merged entity. */
  field: string;
  /**
   * How many distinct entities must share a value before it earns its own
   * node — below this they stay at the parent level rather than sitting in a
   * folder of one. Per-axis rather than global because the right floor
   * genuinely differs: a destination naming a single trip is still the point
   * of an itinerary collection, where a genre holding one title is noise.
   */
  minGroupSize: number;
  /**
   * Maps *all* of one entity's raw values on this axis to the buckets it
   * belongs in — the escape hatch for a split the model never states
   * (Push/Pull/Legs from muscle groups). Identity for every axis that files
   * by what was extracted.
   *
   * Deliberately set-to-set rather than value-to-value: a derivation often
   * depends on the *combination*. A session training chest, back and legs is
   * a full-body session, not three separate ones, and a per-value mapping
   * cannot express that — it would file that session's squat under Push.
   */
  derive?: (rawValues: string[]) => string[];
  /** The bucket's product-facing name. Defaults to title case. */
  displayName?: (value: string) => string;
}

/**
 * Product-facing names for the per-item `kind` vocabulary the registry asks
 * for ("film", "series", "sight"). Closed-ish, in exactly the spirit of
 * `DISPLAY_NAMES`: anything absent falls back to title case, so a kind nobody
 * anticipated still gets a sensible folder instead of disappearing.
 */
const KIND_DISPLAY_NAMES: Record<string, string> = {
  film: 'Movies',
  movie: 'Movies',
  tv: 'Series',
  series: 'Series',
  show: 'Series',
  anime: 'Anime',
  book: 'Books',
  game: 'Games',
  music: 'Music',
  podcast: 'Podcasts',
  place: 'Places',
  sight: 'Sights',
  restaurant: 'Restaurants',
  hotel: 'Hotels',
  area: 'Areas',
  product: 'Products',
  exercise: 'Exercises',
  task: 'Tasks',
};

export function kindDisplayName(kind: string): string {
  return KIND_DISPLAY_NAMES[kind.trim().toLowerCase()] ?? titleCase(kind);
}

/**
 * Synonyms that must bucket together *before* display naming, not just look
 * alike after it. Without this, one save's item carrying `kind: "movie"` and
 * another's carrying `kind: "film"` land in two different buckets — each one
 * correctly labelled "Movies" by `kindDisplayName`, but as two sibling nodes
 * rather than one merged node ("Anime / Movies / Movies" in the tree). Only
 * entries whose raw values genuinely differ need a mapping; every other kind
 * in `KIND_DISPLAY_NAMES` already has one raw spelling.
 */
const KIND_CANONICAL: Record<string, string> = {
  film: 'movie',
  tv: 'series',
  show: 'series',
};

/** The raw `kind` value to bucket by — synonyms folded to one spelling, before display naming. */
export function canonicalKind(kind: string): string {
  const normalised = kind.trim().toLowerCase();
  return KIND_CANONICAL[normalised] ?? normalised;
}

/**
 * Muscle group → training split. The vocabulary is the model's own
 * (`muscleGroups: ['chest', 'shoulders', 'triceps']`) and the mapping is the
 * standard one every push/pull/legs program uses.
 *
 * Anything unrecognised falls through to its own title-cased bucket rather
 * than being dropped — a workout training "grip" still files somewhere, and
 * the split names stay honest instead of forcing every muscle into three
 * folders that may not fit.
 */
const TRAINING_SPLITS: Record<string, string> = {
  chest: 'Push',
  pecs: 'Push',
  shoulders: 'Push',
  shoulder: 'Push',
  delts: 'Push',
  'front delts': 'Push',
  triceps: 'Push',
  tricep: 'Push',
  back: 'Pull',
  lats: 'Pull',
  traps: 'Pull',
  'rear delts': 'Pull',
  biceps: 'Pull',
  bicep: 'Pull',
  forearms: 'Pull',
  legs: 'Legs',
  quads: 'Legs',
  quadriceps: 'Legs',
  hamstrings: 'Legs',
  glutes: 'Legs',
  calves: 'Legs',
  adductors: 'Legs',
  core: 'Core',
  abs: 'Core',
  abdominals: 'Core',
  obliques: 'Core',
};

/** How many distinct splits a session may span before it stops being any one of them. */
const FULL_BODY_THRESHOLD = 3;

/** The name for a session that trains too much to belong to one split. */
export const FULL_BODY = 'Full body';

function splitOf(muscleGroup: string): string | null {
  const normalised = muscleGroup.trim().toLowerCase();
  if (!normalised) return null;
  return TRAINING_SPLITS[normalised] ?? titleCase(muscleGroup);
}

/**
 * A session's muscle groups → the split(s) it belongs to.
 *
 * A session spanning `FULL_BODY_THRESHOLD` or more splits is filed as one
 * `Full body` session rather than into each of them. Without that rule, a
 * full-body circuit's squat appears under Push purely because the same
 * circuit also pressed something — the split is a property of the *session*
 * (it is all the model gives us; there is no per-exercise muscle group), so a
 * session that is all of them is none of them. `Core` does not count toward
 * the span: nearly every session trains it, and letting it push a push day
 * over the line would make "Full body" the only bucket anyone ever sees.
 */
export function trainingSplits(muscleGroups: string[]): string[] {
  const splits: string[] = [];
  for (const muscleGroup of muscleGroups) {
    const split = splitOf(muscleGroup);
    if (split && !splits.includes(split)) splits.push(split);
  }
  const spanning = splits.filter((s) => s !== 'Core').length;
  return spanning >= FULL_BODY_THRESHOLD ? [FULL_BODY] : splits;
}

/**
 * Which of a set of sessions' raw muscle groups actually belong to one named
 * split — "Back, Rear delts, Biceps" for Pull, out of everything a set of
 * push-and-pull days together mention.
 *
 * Deliberately independent of {@link trainingSplits}'s full-body rule: a
 * session that itself got filed as `Full body` never reaches a named split's
 * node in the first place (its entities went to the Full body node instead),
 * so anything reaching this function already belongs to a real split, and
 * asking `splitOf` per muscle is enough — no session-level judgement to
 * repeat here.
 */
export function musclesInSplit(muscleGroups: string[], splitName: string): string[] {
  const target = splitName.trim().toLowerCase();
  if (!target) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const muscleGroup of muscleGroups) {
    const split = splitOf(muscleGroup);
    if (!split || split.toLowerCase() !== target) continue;
    const trimmed = muscleGroup.trim();
    const key = trimmed.toLowerCase();
    if (trimmed && !seen.has(key)) {
      seen.add(key);
      out.push(trimmed);
    }
  }
  return out;
}

/**
 * The axis chain per item-bearing type, outermost first. A type absent here
 * still merges its items into entities — it simply presents them as one flat
 * list, which is K1's behaviour and remains the honest default for a type
 * with no closed-ish vocabulary to split on.
 */
export const AXES: Record<string, CollectionAxis[]> = {
  // What a recommended thing *is*, then what it is *about*. Both read from
  // the merged entity, so a title recommended by an anime list and a film
  // list is filed once rather than once per source.
  recommendation_list: [
    { from: 'entity', field: 'kind', minGroupSize: 2, displayName: kindDisplayName },
    { from: 'entity', field: 'genre', minGroupSize: 2 },
  ],

  // The trip, not the stop. `destination` is save-level: every place in
  // "14 Days in Japan" is a Japan place. minGroupSize 1 because a destination
  // reached by a single save is still the thing the user came here for —
  // "Japan" is the knowledge object, and folding it away would leave its
  // places floating with nothing saying where they are.
  itinerary: [{ from: 'save', field: 'destination', minGroupSize: 1 }],

  checklist: [{ from: 'save', field: 'category', minGroupSize: 2 }],

  // Push/Pull/Legs is not a field any save carries — `category` is
  // Strength/Cardio/Yoga, which does not separate two strength days. It is
  // derived from `muscleGroups` by the fixed table above: pure local compute,
  // zero AI. minGroupSize 1 so a single push day still presents as Push
  // rather than as loose exercises under Workouts.
  workout: [{ from: 'save', field: 'muscleGroups', minGroupSize: 1, derive: trainingSplits }],
};

export function axesFor(knowledgeType: string): CollectionAxis[] {
  return AXES[knowledgeType] ?? [];
}

export function axisDisplayName(axis: CollectionAxis, value: string): string {
  return axis.displayName ? axis.displayName(value) : titleCase(value);
}

/** Applies an axis's derivation to one entity's raw values, de-duplicated and order-preserving. */
export function derivedValues(axis: CollectionAxis, rawValues: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const derived of axis.derive ? axis.derive(rawValues) : rawValues) {
    const trimmed = derived?.trim();
    if (trimmed && !seen.has(trimmed)) {
      seen.add(trimmed);
      out.push(trimmed);
    }
  }
  return out;
}
