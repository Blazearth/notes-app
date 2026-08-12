/**
 * The views the server used to compute per request, computed here instead.
 *
 * `GET /v1/groups` and `GET /v1/collections` each load the user's **entire
 * ready library** as JPA entities and rebuild the whole tree on every call —
 * they are *views* over `saves`, not stored state. Once the store holds the
 * whole library there is nothing left for those requests to tell us, so they
 * stop being made: the tree is derived locally, instantly, and offline.
 *
 * Both endpoints stay in `Repository` and stay implemented by
 * `mockRepository` — they remain API surface, and keeping them keeps mock
 * parity honest. The app simply no longer calls them.
 *
 * **K4's collection overrides are now local too.** L2 passed `EMPTY_OVERRIDES`
 * here for a stated reason — they lived in `collection_overrides` server-side
 * and no endpoint read them back, so the local tree could not have agreed with
 * the server's about a manually merged or renamed entity even in principle.
 * `GET /v1/sync` (L4) is that endpoint, so the reason is gone: the overrides ride
 * the delta into the store's `overrides` table and are threaded through the same
 * pure merge core the server threads them through. Chains are resolved
 * server-side by `CollectionOverrideService.loadFor` before they are sent, so
 * what arrives here is already `A -> C`, never `A -> B -> C`.
 */

import type { CollectionEntityResponse, CollectionNodeResponse, SaveResponse } from '@/api/types';
import {
  buildTree as buildCollectionTree,
  findCollectionNode,
  mergeNode,
  withDoneCount,
  type CollectionOverrides,
  type CollectionSaveFacts,
} from '@/collections/merge';
import { nodeType } from '@/collections/collectionMeta';
import { buildCandidate, rankCandidates, type NextAction } from '@/collections/nextAction';
import type { KnowledgeGroup } from '@/data/repository';
import { buildTree as buildGroupTree, collectSaveIds, findGroup, type GroupSaveFacts } from '@/groups/tree';
import type { LocalStore } from './store';

/** The tables every derived view below depends on — what `useLive` subscribes to. */
export const DERIVED_TABLES = ['saves', 'entity_states', 'overrides'] as const;

/**
 * Both trees are built from *ready* saves only, matching
 * `findByUserIdAndStatusOrderByCreatedAtDesc(userId, READY)` on the server. A
 * still-processing save has no `structuredData` to file, and one that failed is
 * not a category the user asked for.
 */
async function readySaves(store: LocalStore): Promise<SaveResponse[]> {
  const all = await store.readFeed({ includeArchived: false });
  return all.filter((save) => save.status === 'ready');
}

function toGroupFacts(save: SaveResponse): GroupSaveFacts {
  return { id: save.id, knowledgeType: save.knowledgeType, structuredData: save.structuredData ?? {} };
}

function toCollectionFacts(save: SaveResponse): CollectionSaveFacts {
  return {
    id: save.id,
    knowledgeType: save.knowledgeType,
    structuredData: save.structuredData ?? {},
    createdAt: save.createdAt,
  };
}

// ------------------------------------------------------------------ groups

export async function readGroups(store: LocalStore): Promise<KnowledgeGroup[]> {
  return buildGroupTree((await readySaves(store)).map(toGroupFacts));
}

export async function readGroup(store: LocalStore, id: string): Promise<KnowledgeGroup | null> {
  return findGroup(await readGroups(store), id);
}

/**
 * The saves in a group. `deep: false` is what a folder shows — the items at
 * this level, with subgroups listed separately above them.
 */
export async function readGroupSaves(store: LocalStore, id: string, deep = false): Promise<SaveResponse[]> {
  const group = findGroup(await readGroups(store), id);
  if (!group) return [];
  return store.readSavesByIds(deep ? collectSaveIds(group) : group.saveIds);
}

// ------------------------------------------------------------- collections

/** Entity keys the user has marked done — what `doneCount` counts. */
async function doneEntityKeys(store: LocalStore): Promise<Set<string>> {
  const states = await store.readEntityStates();
  const done = new Set<string>();
  for (const [key, state] of Object.entries(states)) {
    if (state?.done === true) done.add(key);
  }
  return done;
}

/**
 * The store's override rows in the shape the merge core wants — the same
 * bucketing `CollectionOverrideService.loadFor` does server-side, minus the
 * chain resolution, which has already happened before these were sent.
 */
async function readOverrides(store: LocalStore): Promise<CollectionOverrides> {
  const mergeRedirects: Record<string, string> = {};
  const entityNames: Record<string, string> = {};
  const collectionNames: Record<string, string> = {};
  for (const row of await store.readOverrides()) {
    const into = row.payload.into;
    const name = row.payload.name;
    if (row.overrideType === 'entity_merge' && typeof into === 'string' && into) {
      mergeRedirects[row.subjectKey] = into;
    } else if (row.overrideType === 'entity_rename' && typeof name === 'string' && name) {
      entityNames[row.subjectKey] = name;
    } else if (row.overrideType === 'collection_rename' && typeof name === 'string' && name) {
      collectionNames[row.subjectKey] = name;
    }
  }
  return { mergeRedirects, entityNames, collectionNames };
}

export async function readCollections(store: LocalStore): Promise<CollectionNodeResponse[]> {
  const tree = buildCollectionTree(
    (await readySaves(store)).map(toCollectionFacts),
    undefined,
    await readOverrides(store),
  );
  const done = await doneEntityKeys(store);
  return tree.map((node) => withDoneCount(node, done));
}

/**
 * One node of the tree, by id — `itinerary` for a type root,
 * `itinerary~japan` for one of its folders. Returns null for an id the
 * current library does not produce, which a screen reads as "not synced
 * yet", never as an error.
 */
export async function readCollectionNode(
  store: LocalStore,
  nodeId: string,
): Promise<CollectionNodeResponse | null> {
  return findCollectionNode(await readCollections(store), nodeId);
}

/**
 * The merged entities under one node — the whole type for a bare type id, one
 * subtree for a path.
 *
 * Note this resolves through `mergeNode`'s axis-path walk rather than through
 * the tree built above, so a node whose subgroup was too small to be worth
 * *showing* still answers with its entities. The two questions are separate,
 * and tying them together made a well-formed id resolve empty.
 */
export async function readCollectionEntities(
  store: LocalStore,
  nodeId: string,
): Promise<CollectionEntityResponse[]> {
  const merged = mergeNode(
    (await readySaves(store)).map(toCollectionFacts),
    nodeId,
    await readOverrides(store),
  );
  const states = await store.readEntityStates();
  // The state join the server does inside `GET /v1/collections/{nodeId}` — the
  // entity payload and the caller's own K2 state arrive together, so no screen
  // has to correlate two lists.
  return merged.map((entity) =>
    states[entity.entityKey] ? { ...entity, state: states[entity.entityKey] } : entity,
  );
}

/**
 * The saves feeding one node — its provenance, for the "mentioned in N
 * sources" relationships that make a collection read as accumulated
 * knowledge rather than a list. Ordered by the feed's own order.
 */
export async function readCollectionSources(
  store: LocalStore,
  nodeId: string,
): Promise<SaveResponse[]> {
  const entities = await readCollectionEntities(store, nodeId);
  const saveIds = new Set<string>();
  entities.forEach((entity) => entity.sources.forEach((source) => saveIds.add(source.saveId)));
  return store.readSavesByIds([...saveIds]);
}

/** Every node with no folders under it — the only nodes a candidate can ever be about. */
function leafNodes(nodes: CollectionNodeResponse[]): CollectionNodeResponse[] {
  const out: CollectionNodeResponse[] = [];
  const walk = (n: CollectionNodeResponse) => {
    if (n.subgroups.length === 0) out.push(n);
    else n.subgroups.forEach(walk);
  };
  nodes.forEach(walk);
  return out;
}

/**
 * Home's single "Today" tile — the highest-weighted `@/collections/nextAction`
 * candidate across every type's whole tree. Walks every leaf once; each leaf's
 * own entities are already a cheap read (`readCollectionEntities` re-merges
 * from the in-memory `readySaves`, not a second store round trip), and a
 * user's tree is small enough that this costs nothing worth memoising
 * further. Returns null when nothing cleared any type's threshold — an empty
 * "Today" is the honest answer, not an error.
 */
export async function readTopNextAction(store: LocalStore): Promise<NextAction | null> {
  const tree = await readCollections(store);
  const candidates: NextAction[] = [];
  for (const leaf of leafNodes(tree)) {
    const entities = await readCollectionEntities(store, leaf.id);
    const candidate = buildCandidate(nodeType(leaf.id), leaf, entities);
    if (candidate) candidates.push(candidate);
  }
  return rankCandidates(candidates);
}

// ------------------------------------------------- Space collections (S1)

/**
 * The same derived merge, over one Space's saves instead of the whole library
 * — `docs/knowledge-spaces.md`'s S1, client side.
 *
 * The server grew the identical capability at S1, and both exist for a reason:
 * the server's is what supplies `addedBy` and every member's state, which no
 * client can derive (a save's owner is not on `SaveResponse`); this one is
 * what paints the screen instantly and offline from saves already in the
 * store. The screen renders this first and overlays the server's richer answer
 * when it lands, which is the same stale-while-revalidate shape every other
 * read here has.
 *
 * **No overrides, matching the server**, and for the same reason: overrides are
 * one *user's* curation, and reshaping a shared view by one member's private
 * renames would show the group something only that member asked for. Passing
 * `readOverrides(store)` here would have been the one-line change that made
 * the two sides disagree.
 */
async function spaceSaveFacts(store: LocalStore, spaceId: string): Promise<CollectionSaveFacts[]> {
  const inSpace = await store.readFeed({ spaceId });
  return inSpace.filter((save) => save.status === 'ready').map(toCollectionFacts);
}

export async function readSpaceCollections(
  store: LocalStore,
  spaceId: string,
): Promise<CollectionNodeResponse[]> {
  return buildCollectionTree(await spaceSaveFacts(store, spaceId));
}

export async function readSpaceCollectionNode(
  store: LocalStore,
  spaceId: string,
  nodeId: string,
): Promise<CollectionNodeResponse | null> {
  return findCollectionNode(await readSpaceCollections(store, spaceId), nodeId);
}

/**
 * One node's merged entities, Space-scoped, with the *viewer's own* state
 * joined in — the same join `readCollectionEntities` does. Everyone else's
 * state comes from the server and is merged in by the screen; there is no
 * local copy of another member's state and there deliberately never will be,
 * since `GET /v1/sync` only ever carries the caller's own.
 */
export async function readSpaceCollectionEntities(
  store: LocalStore,
  spaceId: string,
  nodeId: string,
): Promise<CollectionEntityResponse[]> {
  const merged = mergeNode(await spaceSaveFacts(store, spaceId), nodeId);
  const states = await store.readEntityStates();
  return merged.map((entity) =>
    states[entity.entityKey] ? { ...entity, state: states[entity.entityKey] } : entity,
  );
}

// ---------------------------------------------------------- continue rail

/**
 * Home's Continue rail — `GET /v1/saves/lifecycle`, derived.
 *
 * The rail wants what was last *touched*, which is `updatedAt` order, not the
 * feed's `createdAt`. That distinction is exactly why this used to be its own
 * request: filtering the feed client-side would have missed anything older than
 * page 0. With the whole library local there is nothing left to miss.
 */
export async function readContinueSaves(store: LocalStore, limit = 10): Promise<SaveResponse[]> {
  const all = await store.readFeed({ orderBy: 'updated' });
  return all
    .filter((save) => save.lifecycleStatus === 'planned' || save.lifecycleStatus === 'started')
    .slice(0, limit);
}
