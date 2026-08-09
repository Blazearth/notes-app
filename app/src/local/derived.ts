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
 * **One thing is deliberately not reproduced locally: K4's collection
 * overrides** (manual entity merge and rename). They live in
 * `collection_overrides` server-side, there is no endpoint that reads them
 * back, and nothing in the app writes one today — merge and rename are
 * endpoint-complete but have no picker UI, and *pin* rides `entity_states`
 * (which does sync). So the local tree passes `EMPTY_OVERRIDES` and nothing
 * observable changes. The day a merge UI exists, overrides join the sync
 * alongside entity state.
 */

import type { CollectionEntityResponse, CollectionNodeResponse, SaveResponse } from '@/api/types';
import {
  buildTree as buildCollectionTree,
  mergeType,
  withDoneCount,
  type CollectionSaveFacts,
} from '@/collections/merge';
import type { KnowledgeGroup } from '@/data/repository';
import { buildTree as buildGroupTree, collectSaveIds, findGroup, type GroupSaveFacts } from '@/groups/tree';
import type { LocalStore } from './store';

/** The tables every derived view below depends on — what `useLive` subscribes to. */
export const DERIVED_TABLES = ['saves', 'entity_states'] as const;

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

export async function readCollections(store: LocalStore): Promise<CollectionNodeResponse[]> {
  const tree = buildCollectionTree((await readySaves(store)).map(toCollectionFacts));
  const done = await doneEntityKeys(store);
  return tree.map((node) => withDoneCount(node, done));
}

export async function readCollectionEntities(
  store: LocalStore,
  type: string,
  facet?: string | null,
): Promise<CollectionEntityResponse[]> {
  const merged = mergeType((await readySaves(store)).map(toCollectionFacts), type, facet ?? null);
  const states = await store.readEntityStates();
  // The state join the server does inside `GET /v1/collections/{type}` — the
  // entity payload and the caller's own K2 state arrive together, so no screen
  // has to correlate two lists.
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
