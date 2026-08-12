/**
 * "What should I do next?" — one grounded suggestion per collection leaf,
 * built entirely from data already sitting in the merged tree and its
 * entities. Every reason string names a real signal (source count, recency,
 * status, remaining count); nothing here calls Gemini, and nothing invents
 * content that was not already extracted. Pure local compute, the same cost
 * class as `workoutLoad.ts` and `detailModel.ts`'s own derived fields.
 *
 * Two callers: a leaf `CollectionDetailScreen` builds its own node's
 * candidate directly with {@link buildCandidate}; Home's single cross-type
 * "Today" tile walks every leaf in the whole tree (`@/local/derived`'s
 * `readTopNextAction`) and keeps the highest-{@link NextAction.weight} result
 * with {@link rankCandidates}. Deliberately scarce, per type: a threshold
 * below which nothing is suggested, because a nudge on every screen is a
 * nudge nobody reads.
 *
 * Structural types (`EntityFacts`/`NodeFacts`) rather than importing
 * `CollectionEntityResponse`/`CollectionNodeResponse` from `@/api/types` —
 * same reason `sourceSummary.ts` defines its own `SourceFacts`: this stays
 * import-free of anything but its two sibling pure modules, so it runs
 * standalone under `node --experimental-strip-types` against hand-built
 * fixtures with no path-alias resolution needed.
 *
 * Pure: no store, no theme, no React.
 */

import { currentStatus } from './entityStatus';
import { estimateSessionLoad } from './workoutLoad';

export interface EntitySourceFacts {
  saveId: string;
  savedAt: string;
}

export interface EntityFacts {
  entityKey: string;
  name: string;
  fields: Record<string, unknown>;
  sources: EntitySourceFacts[];
  sourceCount: number;
  state?: Record<string, unknown> | null;
}

export interface NodeFacts {
  id: string;
  name: string;
  entityCount: number;
  doneCount: number;
  sourceCount: number;
  subgroups: NodeFacts[];
}

export interface NextAction {
  /** The node this action is about — what a "View" tap navigates to. */
  nodeId: string;
  type: string;
  /** The one thing to do — an entity name for a pick, a split/destination name otherwise. */
  headline: string;
  /** One line of grounded context under the headline. */
  detail: string;
  actionLabel: string;
  /** Set only when the action is about one particular entity (recommendation picks). */
  entityKey?: string;
  /** Grounded, data-only bullets for an expandable "Why?" — never a generated explanation. */
  reasons: string[];
  /** Ranking weight across types/nodes on Home — larger sorts first. Meaningless in isolation. */
  weight: number;
}

const MIN_RECOMMENDATION_CANDIDATES = 3;
const MIN_WORKOUT_EXERCISES = 2;
const MIN_ITINERARY_PLACES = 3;
const MIN_CHECKLIST_REMAINING = 2;

function isDone(state: Record<string, unknown> | null | undefined): boolean {
  return state?.done === true;
}

function latestSavedAt(entity: EntityFacts): string {
  return entity.sources.reduce((latest, s) => (s.savedAt > latest ? s.savedAt : latest), '');
}

/**
 * Recommendations: the strongest not-yet-watched (or already-watching) title
 * in a leaf category — "watching" first (finish what was started), then most
 * sources agreeing, then most recently saved. Nothing here is a taste model;
 * it is a sort over fields the merge already produced.
 */
function pickRecommendation(node: NodeFacts, entities: EntityFacts[]): NextAction | null {
  const open = entities.filter((e) => !isDone(e.state));
  if (open.length < MIN_RECOMMENDATION_CANDIDATES) return null;

  const mostRecent = open.reduce((a, b) => (latestSavedAt(b) > latestSavedAt(a) ? b : a));
  const sorted = [...open].sort((a, b) => {
    const aWatching = currentStatus('recommendation_list', a.state)?.key === 'watching';
    const bWatching = currentStatus('recommendation_list', b.state)?.key === 'watching';
    if (aWatching !== bWatching) return aWatching ? -1 : 1;
    if (b.sourceCount !== a.sourceCount) return b.sourceCount - a.sourceCount;
    return latestSavedAt(b).localeCompare(latestSavedAt(a));
  });
  const picked = sorted[0];
  const status = currentStatus('recommendation_list', picked.state);
  const watching = status?.key === 'watching';

  const reasons: string[] = [];
  if (picked.sourceCount > 1) {
    reasons.push(`Appears in ${picked.sourceCount} of your saved sources`);
  }
  reasons.push(`Matches your ${node.name} collection`);
  reasons.push(watching ? 'You already started this one' : "You haven't watched it yet");
  if (picked.entityKey === mostRecent.entityKey && open.length > 1) {
    reasons.push('Saved more recently than the others');
  }

  return {
    nodeId: node.id,
    type: 'recommendation_list',
    headline: picked.name,
    detail: `You saved ${node.entityCount} in ${node.name}${watching ? '. Pick up where you left off.' : '.'}`,
    actionLabel: watching ? 'Continue watching' : 'Watch this',
    entityKey: picked.entityKey,
    reasons,
    weight: open.length * 10 + picked.sourceCount,
  };
}

/** Workouts: the split's own session load, using the same estimate the collection and save-detail screens already show. */
function pickWorkout(node: NodeFacts, entities: EntityFacts[]): NextAction | null {
  if (entities.length < MIN_WORKOUT_EXERCISES) return null;
  const load = estimateSessionLoad(entities.map((e) => e.fields));

  const reasons = [
    `${node.entityCount} exercise${node.entityCount === 1 ? '' : 's'} saved under ${node.name}`,
    `From ${node.sourceCount} saved workout${node.sourceCount === 1 ? '' : 's'}`,
  ];

  return {
    nodeId: node.id,
    type: 'workout',
    headline: `Start ${node.name} day`,
    detail: load
      ? `${load.totalSets} sets across ${load.countedExercises} exercise${load.countedExercises === 1 ? '' : 's'} · ~${load.estimatedMinutes} min (est.)`
      : `${entities.length} exercise${entities.length === 1 ? '' : 's'}`,
    actionLabel: 'Start workout',
    reasons,
    weight: entities.length * 10 + node.sourceCount,
  };
}

/**
 * Itineraries: a nudge to look at a destination with enough saved places to
 * be worth planning around — never a generated route or day count. Building
 * an actual day-by-day plan would be synthesising a new object across
 * sources, which stays out of scope here the same way it stays out of the
 * collection merge itself (an itinerary's places union; nobody invents an
 * order for them).
 */
function pickItinerary(node: NodeFacts, entities: EntityFacts[]): NextAction | null {
  if (entities.length < MIN_ITINERARY_PLACES) return null;

  const reasons = [
    `${node.entityCount} places saved for ${node.name}`,
    `From ${node.sourceCount} saved itinerar${node.sourceCount === 1 ? 'y' : 'ies'}`,
  ];

  return {
    nodeId: node.id,
    type: 'itinerary',
    headline: `Plan your time in ${node.name}`,
    detail: `${node.entityCount} places saved here, from ${node.sourceCount} of your itineraries.`,
    actionLabel: 'View places',
    reasons,
    weight: entities.length * 8 + node.sourceCount,
  };
}

/** Checklists: a nudge to finish a list that is genuinely underway, not one just started or already done. */
function pickChecklist(node: NodeFacts, entities: EntityFacts[]): NextAction | null {
  const remaining = entities.filter((e) => !isDone(e.state)).length;
  if (remaining < MIN_CHECKLIST_REMAINING || remaining >= entities.length) return null;

  const done = entities.length - remaining;
  const reasons = [
    `${done} of ${entities.length} done in ${node.name}`,
    `From ${node.sourceCount} saved list${node.sourceCount === 1 ? '' : 's'}`,
  ];

  return {
    nodeId: node.id,
    type: 'checklist',
    headline: `Finish ${node.name}`,
    detail: `${done}/${entities.length} done — ${remaining} left.`,
    actionLabel: 'View checklist',
    reasons,
    weight: remaining * 6 + node.sourceCount,
  };
}

const PICKERS: Record<string, (node: NodeFacts, entities: EntityFacts[]) => NextAction | null> = {
  recommendation_list: pickRecommendation,
  workout: pickWorkout,
  itinerary: pickItinerary,
  checklist: pickChecklist,
};

/**
 * One node's candidate, or null when the type has no picker, the node still
 * has folders under it (a candidate only ever names a leaf — "Romance", not
 * "Recommendations"), or the type's own threshold was not met.
 */
export function buildCandidate(type: string, node: NodeFacts, entities: EntityFacts[]): NextAction | null {
  if (node.subgroups.length > 0) return null;
  return PICKERS[type]?.(node, entities) ?? null;
}

/** The single most worth-surfacing candidate, or null when none cleared their threshold. */
export function rankCandidates(candidates: NextAction[]): NextAction | null {
  if (candidates.length === 0) return null;
  return [...candidates].sort((a, b) => b.weight - a.weight || a.nodeId.localeCompare(b.nodeId))[0];
}
