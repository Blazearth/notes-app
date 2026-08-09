/**
 * The Space Overview's model — S0 of [docs/knowledge-spaces.md](../../../docs/knowledge-spaces.md).
 *
 * **What the redesign is for**, stated once so this file's narrowness makes
 * sense: a Space today is a folder of saves with people attached, and
 * collaborators don't care that Aryan saved Reel #17 — they care about the
 * anime the group should watch. The Space's primary surface becomes the
 * merged knowledge built from everyone's sources; the saves become evidence.
 * Overview *is* that surface, which is why it is the tab a Space opens on.
 *
 * **S0 is the frame, deliberately.** Everything here is a count over saves the
 * screen already holds locally — no request, no AI, no new server work. The
 * two things it stops short of are the two that need S1/S2:
 *
 * - **No merged entity list.** The rows below say "Recommendations · 3 titles
 *   · 2 sources", not which three. The list itself is S2, because a shared
 *   watchlist without per-member status ("Watching by Aryan") is the personal
 *   collection screen with a Space's name on it.
 * - **No done counts.** `entity_states` is per `(user_id, entity_key)` and
 *   global, so the only done count available here is the *viewer's* — and
 *   "1 watched" on a group's Overview reads as a claim about the group. S2's
 *   batched all-members read is what makes that number mean what it looks
 *   like, and S2 is also where the disclosure question it raises gets decided.
 *
 * **The merge is the same merge, not a second one.** `@/collections/merge` is
 * already the client-side port of `CollectionService`, so a Space-scoped
 * collection is that function over a different save set — which is exactly
 * what S1 makes the server do too (`CollectionService` gains a scope
 * parameter; it does not gain a Spaces implementation). Deriving it here
 * costs nothing and cannot disagree with the Sources tab beside it.
 *
 * Pure and dependency-free apart from the merge core, following the
 * `detailModel.ts` convention: it can be compiled alone and executed under
 * node (`docs/testing.md`), which is this app's only way to run logic.
 */

import type { SaveResponse } from '@/api/types';
import { buildTree, type CollectionNode, type CollectionSaveFacts } from '@/collections/merge';

/**
 * One derived collection in a Space — a top-level `CollectionNode` reduced to
 * what a summary row shows. `type` is the node id at top level
 * (`CollectionController`'s scheme), so it keys `saveTypeMeta` directly.
 */
export interface SpaceCollectionSummary {
  type: string;
  name: string;
  /** Distinct entities in the whole subtree — never a sum of the subgroups. */
  entityCount: number;
  /** Distinct saves feeding it. "Blue Box, recommended in 2 sources." */
  sourceCount: number;
}

/** One knowledge type present in the Space, with how many saves carry it. */
export interface SpaceTypeCount {
  type: string;
  count: number;
}

export interface SpaceOverview {
  /** Descending by entity count. Empty when nothing in the Space merges. */
  collections: SpaceCollectionSummary[];
  /** Every ready save's knowledge type, descending by count. */
  types: SpaceTypeCount[];
  /** Distinct entities across every collection — see `distinctEntityKeys`. */
  entityCount: number;
  /** Every save in the Space, whatever its status: the Sources tab's count. */
  sourceCount: number;
  readyCount: number;
  /** `processing` + `pending` — still on their way to being knowledge. */
  workingCount: number;
  failedCount: number;
  memberCount: number;
}

export interface SpaceOverviewInput {
  /** The Space's saves, any status. Archived ones are the caller's business. */
  saves: SaveResponse[];
  /** `Space.memberCount` — server-counted, so it is right even before the member list loads. */
  memberCount: number;
}

function toCollectionFacts(save: SaveResponse): CollectionSaveFacts {
  return {
    id: save.id,
    knowledgeType: save.knowledgeType,
    structuredData: save.structuredData ?? {},
    createdAt: save.createdAt,
  };
}

/**
 * Every entity key beneath a node, its subgroups included.
 *
 * A node's own `entityKeys` holds only what sits loose at its level, so
 * summing them across a tree under-counts and summing `entityCount` across
 * types over-counts anything two types happen to key identically. Collecting
 * into a set is the same distinct-not-sum rule `CollectionNode.of` and
 * `GroupService.itemCount` already keep — a folder is asked how many things
 * are in it, not how many places we filed them.
 */
function distinctEntityKeys(node: CollectionNode, into: Set<string>): void {
  node.entityKeys.forEach((key) => into.add(key));
  node.subgroups.forEach((child) => distinctEntityKeys(child, into));
}

/**
 * Counts by knowledge type over ready saves — the "what's actually in here"
 * line for a Space whose content does not merge (a trip's places and notes),
 * which is most Spaces until someone saves a list.
 */
function typeCounts(ready: SaveResponse[]): SpaceTypeCount[] {
  const counts = new Map<string, number>();
  for (const save of ready) {
    const type = save.knowledgeType?.trim().toLowerCase();
    if (!type) continue;
    counts.set(type, (counts.get(type) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count || (a.type < b.type ? -1 : 1));
}

export function buildSpaceOverview({ saves, memberCount }: SpaceOverviewInput): SpaceOverview {
  // Ready only, matching the server's own `findByUserIdAndStatusOrderBy…(READY)`
  // and `@/local/derived`: a still-processing save has no `structuredData` to
  // merge, and a failed one is not knowledge the group has.
  const ready = saves.filter((save) => save.status === 'ready');

  const tree = buildTree(ready.map(toCollectionFacts));
  const allKeys = new Set<string>();
  tree.forEach((node) => distinctEntityKeys(node, allKeys));

  const collections: SpaceCollectionSummary[] = tree
    .map((node) => ({
      type: node.id,
      name: node.name,
      entityCount: node.entityCount,
      sourceCount: node.sourceCount,
    }))
    .sort((a, b) => b.entityCount - a.entityCount || (a.name < b.name ? -1 : 1));

  return {
    collections,
    types: typeCounts(ready),
    entityCount: allKeys.size,
    sourceCount: saves.length,
    readyCount: ready.length,
    workingCount: saves.filter((s) => s.status === 'processing' || s.status === 'pending').length,
    failedCount: saves.filter((s) => s.status === 'failed').length,
    memberCount,
  };
}

/**
 * Which tab a Space opens on.
 *
 * Overview only once the Space's saves actually yield merged knowledge —
 * otherwise a brand-new Space, or one holding only unmergeable types, opens
 * onto a dashboard of zeroes and the user has to find their way back to the
 * content. Per `docs/knowledge-spaces.md`'s IA section, verbatim.
 */
export function spaceDefaultTab(overview: SpaceOverview): 'overview' | 'sources' {
  return overview.collections.length > 0 ? 'overview' : 'sources';
}
