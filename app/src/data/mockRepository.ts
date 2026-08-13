/**
 * The whole app, with no network and no database.
 *
 * **Stateful on purpose.** The obvious implementation returns the fixtures
 * verbatim from every method, and it is subtly useless: creating a save shows
 * nothing new, ticking a shopping item un-ticks itself on the next read, and
 * every optimistic-update bug in the UI passes. This holds the fixtures in
 * module-scope arrays and mutates them, so a change made on one screen is
 * visible on the next — which is the only way the flows are worth clicking
 * through at all. State resets on reload, which is the right lifetime for it.
 *
 * Latency is simulated (`MOCK_LATENCY_MS`) rather than instant, so loading
 * states actually render. See `config.ts` for why that matters.
 */

import type {
  ActivityEntry,
  CollectionEntityResponse,
  CollectionNodeResponse,
  CreateSaveRequest,
  DigestResponse,
  DuplicateSuggestion,
  EntityComment,
  InvitePreview,
  LifecycleStatus,
  MeResponse,
  SaveComment,
  SaveResponse,
  SearchHit,
  ShoppingListResponse,
  Space,
  SpaceEntityResponse,
  SpaceInvite,
  SpaceKnowledgeOverview,
  SpaceMember,
  SpaceMemberState,
  SpaceCommentEntry,
  SpacePin,
  SpaceRole,
} from '@/api/types';
import { ApiError } from '@/api/client';
import {
  buildTree,
  mergeNode,
  mergeType,
  withDoneCount,
  type CollectionOverrides,
  type CollectionSaveFacts,
} from '@/collections/merge';
import { rollUpMemberProgress } from '@/spaces/spaceProgress';
import { MOCK_LATENCY_MS } from './config';
import {
  MOCK_ACTIVITY,
  MOCK_COMMENTS,
  MOCK_GROUPS,
  MOCK_MEMBER_ENTITY_STATES,
  MOCK_MEMBERS,
  MOCK_SAVE_OWNERS,
  MOCK_SAVES,
  MOCK_SHOPPING_LIST,
  MOCK_SPACES,
  MOCK_USER_ID,
  MOCK_WEEKLY_DIGEST,
} from './mockData';
import type { KnowledgeGroup, Repository } from './repository';

/** Resolves after a plausible round trip. */
function delay<T>(value: T): Promise<T> {
  const { min, max } = MOCK_LATENCY_MS;
  const ms = min + Math.random() * (max - min);
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

/**
 * Deep-ish copy on the way out.
 *
 * Without it a screen that mutates what it was handed silently edits the
 * fixture store, and the resulting bug looks like a backend problem. Cheap at
 * these sizes and worth the certainty.
 */
function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

/** Monday of the current ISO week, UTC — mirrors `UsageService.weekStart()` server-side. */
function mondayOfThisWeek(): string {
  const now = new Date();
  const day = now.getUTCDay(); // 0 = Sunday
  const diffToMonday = day === 0 ? 6 : day - 1;
  const monday = new Date(now);
  monday.setUTCDate(now.getUTCDate() - diffToMonday);
  return monday.toISOString().slice(0, 10);
}

let idCounter = 100;
const nextId = (prefix: string) => `${prefix}-${++idCounter}`;
const now = () => new Date().toISOString();

// Mutable stores, seeded from the fixtures.
//
// `userId` is stamped on here rather than in the fixture data itself:
// `SavesProvider` filters the personal feed by `ownedByUserId` (added
// 2026-08-11 to keep a Space-mate's saves out of Home/Library), and every
// `MOCK_SAVES` entry is Maya's own save — none of the fixtures ever carried
// the field, which silently emptied both feeds in mock mode until this line
// existed. One stamp here covers every save rather than 19 fixture edits.
const saves: SaveResponse[] = copy(MOCK_SAVES).map((save) => ({ ...save, userId: save.userId ?? MOCK_USER_ID }));
const spaces: Space[] = copy(MOCK_SPACES);
const members: Record<string, SpaceMember[]> = copy(MOCK_MEMBERS);
const activity: Record<string, ActivityEntry[]> = copy(MOCK_ACTIVITY);
const comments: Record<string, SaveComment[]> = copy(MOCK_COMMENTS);
const shoppingList: ShoppingListResponse = copy(MOCK_SHOPPING_LIST);
const votes: Record<string, number> = {};
/**
 * S3 — `entity_comments`, keyed by Space. Space-scoped, unlike `entityStates`
 * below, and that shape difference is the feature: a status is a fact about a
 * person and travels with them, a remark was said in a room and stays there.
 */
const entityComments: Record<string, EntityComment[]> = {};
/** S4 — `space_pins`, keyed by Space. */
const spacePins: Record<string, SpacePin[]> = {};
/** S4 — one shared shopping list per Space, alongside the personal one above. */
const spaceShoppingLists: Record<string, ShoppingListResponse> = {};
/** K2 entity state, keyed by `Entities.key`'s output — mirrors `entity_states`. */
const entityStates: Record<string, Record<string, unknown>> = {};

/**
 * `Idempotency-Key` → the save it created — mirrors V2's partial unique index.
 *
 * Worth mocking rather than ignoring: L3's outbox retries a `createSave` with
 * the same key after a lost response, and a mock that minted a second save on
 * the replay would make the one bug the key exists to prevent invisible in the
 * only environment this project can drive end to end.
 */
const savesByIdempotencyKey: Record<string, string> = {};

/**
 * `tombstones` — mirrors V15. Pushed to by the delete paths below so that mock
 * mode exercises the client's deletion-apply path rather than only its upserts;
 * a delta whose deletions are never tested is the half most likely to be wrong.
 */
const tombstones: { type: string; id: string; at: string }[] = [];

/** K4c curation — mirrors `collection_overrides`. Chained merges are resolved to their final target on write, same as `CollectionOverrideService.resolveChains`. */
const mergeRedirects: Record<string, string> = {};
const entityNameOverrides: Record<string, string> = {};
const collectionNameOverrides: Record<string, string> = {};

/** Chases a chain of merges to its final target — mirrors `CollectionOverrideService.resolveChains`' cycle guard. */
function resolveChain(key: string): string {
  let current = key;
  const seen = new Set<string>();
  while (mergeRedirects[current] && !seen.has(current) && seen.size <= 32) {
    seen.add(current);
    current = mergeRedirects[current];
  }
  return current;
}

/** Every raw redirect resolved to its final target, computed fresh on every read — mirrors `CollectionOverrideService.loadFor`. */
function currentOverrides(): CollectionOverrides {
  const resolved: Record<string, string> = {};
  Object.keys(mergeRedirects).forEach((key) => {
    resolved[key] = resolveChain(key);
  });
  return { mergeRedirects: resolved, entityNames: { ...entityNameOverrides }, collectionNames: { ...collectionNameOverrides } };
}

/** `saves` reshaped into what `@/collections/merge`'s pure functions need — mirrors `CollectionService.loadReady`. */
function readySaveFacts(): CollectionSaveFacts[] {
  return saves
    .filter((s) => s.status === 'ready')
    .map((s) => ({
      id: s.id,
      knowledgeType: s.knowledgeType,
      structuredData: s.structuredData ?? {},
      createdAt: s.createdAt,
    }));
}

/**
 * One Space's ready saves, as merge facts — mirrors
 * `SpaceKnowledgeService.readySaves`. Filtered on the save's own `spaceId`,
 * like every other Space read here.
 */
function spaceSaveFacts(spaceId: string): CollectionSaveFacts[] {
  return saves
    .filter((s) => s.spaceId === spaceId && s.status === 'ready')
    .map((s) => ({
      id: s.id,
      knowledgeType: s.knowledgeType,
      structuredData: s.structuredData ?? {},
      createdAt: s.createdAt,
    }));
}

/**
 * Every member's state for the given entity keys — the mock's stand-in for
 * S2's `space_members` × `entity_states` join.
 *
 * The signed-in user's own states come from the mutable `entityStates` above,
 * so a tap on the Space screen moves the group's numbers immediately; everyone
 * else's come from the fixture, which is what a tap can never change. Members
 * with no state simply do not appear, exactly like the real join.
 */
function spaceMemberStates(
  spaceId: string,
  entityKeys: ReadonlySet<string>,
): Record<string, SpaceMemberState[]> {
  const roster = members[spaceId] ?? [];
  const byEntity: Record<string, SpaceMemberState[]> = {};
  entityKeys.forEach((key) => {
    const fixture = MOCK_MEMBER_ENTITY_STATES[key] ?? {};
    const forEntity: SpaceMemberState[] = [];
    for (const member of roster) {
      const state =
        member.userId === MOCK_USER_ID ? entityStates[key] : fixture[member.userId];
      if (state) forEntity.push({ userId: member.userId, displayName: member.displayName, state });
    }
    if (forEntity.length > 0) byEntity[key] = forEntity;
  });
  return byEntity;
}

/** Entities anyone in the Space has finished — mirrors `SpaceKnowledgeService.doneKeys`. */
function doneAcrossMembers(states: Record<string, SpaceMemberState[]>): Set<string> {
  const done = new Set<string>();
  for (const [key, forEntity] of Object.entries(states)) {
    if (forEntity.some((m) => m.state?.done === true)) done.add(key);
  }
  return done;
}

/**
 * Latest comments across a Space's saves — the join
 * `SpaceKnowledgeService.recentComments` does in SQL, done here by walking the
 * per-save comment fixture, so the mock cannot show discussion from a save
 * that is not in the Space.
 */
function spaceComments(spaceId: string, limit: number): SpaceCommentEntry[] {
  const inSpace = saves.filter((s) => s.spaceId === spaceId);
  const entries: SpaceCommentEntry[] = [];
  for (const save of inSpace) {
    for (const comment of comments[save.id] ?? []) {
      entries.push({
        id: comment.id,
        saveId: save.id,
        saveTitle:
          (save.structuredData?.title as string | undefined) ??
          (save.structuredData?.name as string | undefined),
        userId: comment.userId,
        displayName: comment.displayName,
        body: comment.body,
        createdAt: comment.createdAt,
      });
    }
  }
  // S3's half of the same block. Interleaved by time and capped together —
  // "what's being talked about in here" is one question, so a remark on Blue
  // Box and a remark on the Reel that mentioned it compete for the same rows,
  // exactly as `SpaceKnowledgeService.discussion` merges them.
  for (const comment of entityComments[spaceId] ?? []) {
    entries.push({
      id: comment.id,
      entityKey: comment.entityKey,
      entityName: entityDisplayName(spaceId, comment.entityKey),
      userId: comment.userId,
      displayName: comment.displayName,
      body: comment.body,
      createdAt: comment.createdAt,
    });
  }
  return entries
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, Math.max(1, limit));
}

/**
 * An entity key's readable name, by re-merging the Space — the same thing
 * `SpaceKnowledgeService.entityNames` does, and for the same reason: a key is
 * `"screen:blue box"`, casefolded, and the name only exists on the merged
 * entity.
 */
function entityDisplayName(spaceId: string, entityKey: string): string {
  const type = entityKey.includes(':') ? entityKey.slice(entityKey.indexOf(':') + 1) : entityKey;
  const facts = spaceSaveFacts(spaceId);
  for (const node of buildTree(facts)) {
    const found = mergeNode(facts, node.id).find((e) => e.entityKey === entityKey);
    if (found) return found.name;
  }
  return type;
}

/**
 * A Space's shared list, created on first use — mirrors
 * `ShoppingListService.openListId(userId, spaceId)`.
 *
 * The aisle vocabulary is copied from the personal list rather than re-declared:
 * it ships with the payload precisely so a new aisle is a server change and
 * nothing else, and two mock lists disagreeing about it would be a second
 * source of truth for the one thing that is explicitly meant to have one.
 */
function spaceListFor(spaceId: string): ShoppingListResponse {
  if (!spaceShoppingLists[spaceId]) {
    spaceShoppingLists[spaceId] = {
      id: nextId('sl'),
      items: [],
      categories: shoppingList.categories,
    };
  }
  return spaceShoppingLists[spaceId];
}

/** Every list an item could be on. See `setShoppingItemChecked` for why both scopes are searched. */
function allShoppingLists(): ShoppingListResponse[] {
  return [shoppingList, ...Object.values(spaceShoppingLists)];
}

/** Which entity keys the caller has marked `done: true` — mirrors `CollectionService.doneEntityKeys`. */
function doneEntityKeys(): Set<string> {
  const done = new Set<string>();
  for (const [key, state] of Object.entries(entityStates)) {
    if (state.done === true) done.add(key);
  }
  return done;
}

function tomb(type: string, id: string): void {
  tombstones.push({ type, id, at: now() });
}

function requireSave(id: string): SaveResponse {
  const found = saves.find((s) => s.id === id);
  if (!found) throw new ApiError('notFound', 'That save could not be found.', 404);
  return found;
}

function requireSpace(id: string): Space {
  const found = spaces.find((s) => s.id === id);
  if (!found) throw new ApiError('notFound', 'That space could not be found.', 404);
  return found;
}

export const mockRepository: Repository = {
  // ---------------------------------------------------------------- saves

  createSave(body: CreateSaveRequest, idempotencyKey?: string): Promise<SaveResponse> {
    if (idempotencyKey) {
      const existingId = savesByIdempotencyKey[idempotencyKey];
      const existing = existingId ? saves.find((s) => s.id === existingId) : undefined;
      // The replay case, exactly as `SaveService.create` handles it: the same
      // key returns the save it already made instead of minting a second one.
      if (existing) return delay(copy(existing));
    }
    const save: SaveResponse = {
      id: nextId('sv'),
      // Every fixture save gets this stamp at seed time (see the `saves`
      // initializer above) so `SavesProvider`'s `ownedByUserId` filter can see
      // it; a save minted here has to carry it too, or a save created during
      // this session would be invisible in the mock user's own feed the
      // instant it's made.
      userId: MOCK_USER_ID,
      sourceType: body.sourceType,
      sourceUrl: body.sourceUrl,
      spaceId: body.spaceId,
      // `processing`, like the real thing: a save is never ready on creation,
      // and a mock that returns `ready` would hide the entire status UI.
      status: 'processing',
      favorite: false,
      archived: false,
      createdAt: now(),
      updatedAt: now(),
    };
    saves.unshift(save);
    if (idempotencyKey) savesByIdempotencyKey[idempotencyKey] = save.id;

    // The pipeline, compressed. Lets the feed's processing → ready transition
    // be watched without a backend, which is otherwise untestable here.
    setTimeout(() => {
      const target = saves.find((s) => s.id === save.id);
      if (!target) return;
      target.status = 'ready';
      target.knowledgeType = 'other';
      target.confidence = 0.86;
      target.lifecycleStatus = 'saved';
      target.structuredData = {
        title: body.sourceUrl ? new URL(body.sourceUrl).hostname.replace(/^www\./, '') : 'Quick note',
        summary: body.text ?? 'Saved from a link. The pipeline would fill this in.',
      };
      target.updatedAt = now();
    }, 2500);

    return delay(copy(save));
  },

  listSaves(page = 0, size = 25): Promise<SaveResponse[]> {
    return delay(copy(saves.slice(page * size, page * size + size)));
  },

  listSavesByLifecycle(statuses: LifecycleStatus[], size = 10): Promise<SaveResponse[]> {
    const matching = saves
      .filter((s) => s.lifecycleStatus && statuses.includes(s.lifecycleStatus))
      // Ordered by `updatedAt`, as the server does — the rail is what you last
      // touched, not what you last created.
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .slice(0, size);
    return delay(copy(matching));
  },

  getSave(id: string): Promise<SaveResponse> {
    return delay(copy(requireSave(id)));
  },

  setSaveLifecycle(id: string, lifecycleStatus: LifecycleStatus): Promise<SaveResponse> {
    const save = requireSave(id);
    save.lifecycleStatus = lifecycleStatus;
    save.updatedAt = now();
    return delay(copy(save));
  },

  setSaveFlags(id: string, flags: { favorite?: boolean; archived?: boolean }): Promise<SaveResponse> {
    const save = requireSave(id);
    if (flags.favorite !== undefined) save.favorite = flags.favorite;
    if (flags.archived !== undefined) save.archived = flags.archived;
    save.updatedAt = now();
    return delay(copy(save));
  },

  updateNote(id: string, data: { title?: string; body?: string }): Promise<SaveResponse> {
    const save = requireSave(id);
    save.structuredData = { ...(save.structuredData ?? {}), ...data };
    if (data.body !== undefined) save.rawCaption = data.body;
    save.updatedAt = now();
    return delay(copy(save));
  },

  setSaveSpace(id: string, spaceId: string | null): Promise<SaveResponse> {
    const save = requireSave(id);
    save.spaceId = spaceId ?? undefined;
    save.updatedAt = now();
    return delay(copy(save));
  },

  deleteSave(id: string): Promise<void> {
    const idx = saves.findIndex((s) => s.id === id);
    if (idx >= 0) saves.splice(idx, 1);
    return delay(undefined as void);
  },

  setSaveItemState(
    id: string,
    itemPath: string,
    state: Record<string, unknown>,
  ): Promise<SaveResponse> {
    const save = requireSave(id);
    // Full replace, never a merge — the same rule the real endpoint follows.
    save.itemStates = { ...(save.itemStates ?? {}), [itemPath]: state };
    save.updatedAt = now();
    return delay(copy(save));
  },

  searchSaves(query: string, limit = 25): Promise<SearchHit[]> {
    const q = query.trim().toLowerCase();
    if (!q) return delay([]);

    const hits: SearchHit[] = [];
    for (const save of saves) {
      if (save.status !== 'ready') continue; // Only `ready` saves are searchable.
      const haystack = JSON.stringify(save.structuredData ?? {}).toLowerCase();
      const inText = haystack.includes(q) || (save.sourceUrl ?? '').toLowerCase().includes(q);

      // A crude stand-in for the vector half: a couple of hand-wired
      // associations, so the "related" badge has something to render and the
      // semantic-only path is actually exercised.
      const semantic = SEMANTIC_HINTS.some(
        (hint) => q.includes(hint.term) && hint.saveIds.includes(save.id),
      );

      if (inText || semantic) {
        hits.push({ save: copy(save), match: inText && semantic ? 'both' : inText ? 'text' : 'semantic' });
      }
    }
    return delay(hits.slice(0, limit));
  },

  /**
   * No embeddings exist in mock mode, so this stands in for the real
   * pgvector query with the cheapest signal that still exercises the "you
   * also saved" rail honestly: same `knowledgeType`, excluding the source
   * itself and anything not `ready`. Real relevance (a shared cuisine, a
   * shared genre) is exactly what the real embedding buys over this.
   */
  getRelatedSaves(id: string, limit = 10): Promise<SaveResponse[]> {
    const source = saves.find((s) => s.id === id);
    if (!source || !source.knowledgeType) return delay([]);
    const related = saves
      .filter((s) => s.id !== id && s.status === 'ready' && s.knowledgeType === source.knowledgeType)
      .slice(0, limit);
    return delay(copy(related));
  },

  // -------------------------------------------------------------- account

  getMe(): Promise<MeResponse> {
    return delay<MeResponse>({
      userId: MOCK_USER_ID,
      pro: false,
      entitlement: undefined,
      savesUsed: saves.length,
      savesLimit: 20,
      actsUsed: 1,
      actsLimit: 1,
    });
  },

  /** Empties the two arrays a signed-out session would otherwise still see. */
  deleteAccount(): Promise<void> {
    saves.splice(0, saves.length);
    spaces.splice(0, spaces.length);
    return delay(undefined);
  },

  getWeeklyDigest(): Promise<DigestResponse> {
    return delay<DigestResponse>({
      summary: MOCK_WEEKLY_DIGEST,
      saveCount: saves.length,
      weekStart: mondayOfThisWeek(),
      status: 'ready',
    });
  },

  // ------------------------------------------------------------ the Act

  convertToShoppingList(saveId: string): Promise<{ status: string }> {
    const save = requireSave(saveId);
    // Both ingredient shapes, same as the server: legacy flat strings and the
    // post-2026-08-07 {name, quantity, note} objects.
    const ingredients = (Array.isArray(save.structuredData?.ingredients)
      ? save.structuredData.ingredients
      : []
    )
      .map((item) => {
        if (typeof item === 'string') return item;
        if (typeof item === 'object' && item !== null) {
          const parts = item as { name?: unknown; quantity?: unknown; note?: unknown };
          const clean = (v: unknown) =>
            typeof v === 'string' && v.trim() && v.trim() !== '[unclear]' ? v.trim() : null;
          const name = clean(parts.name);
          if (!name) return null;
          const quantity = clean(parts.quantity);
          const note = clean(parts.note);
          return `${quantity ? `${quantity} ` : ''}${name}${note ? `, ${note}` : ''}`;
        }
        return null;
      })
      .filter((v): v is string => v !== null);

    // S4: the target follows the *save*, not the caller — a recipe in a Space
    // feeds the Space's shared list, so the same recipe always lands in the
    // same place however many members convert it.
    const list = save.spaceId ? spaceListFor(save.spaceId) : shoppingList;

    // Contribution-per-save, exactly as the server folds it: re-converting the
    // same recipe replaces its lines instead of doubling every quantity. That
    // property is what makes two *people's* recipes merge correctly too.
    for (const item of list.items) {
      item.sources = item.sources.filter((s) => s !== saveId);
    }
    let index = 0;
    for (const line of ingredients) {
      const existing = list.items.find((i) => line.toLowerCase().includes(i.name.toLowerCase()));
      if (existing) {
        if (!existing.sources.includes(saveId)) existing.sources.push(saveId);
      } else {
        list.items.push({
          id: nextId('it'),
          name: line,
          category: list.categories[index % list.categories.length],
          checked: false,
          sources: [saveId],
        });
      }
      index += 1;
    }
    list.items = list.items.filter((i) => i.sources.length > 0);
    return delay({ status: 'accepted' });
  },

  getShoppingList(): Promise<ShoppingListResponse> {
    return delay(copy(shoppingList));
  },

  /**
   * An item id addresses exactly one row on exactly one list, which is why the
   * server needs no Space variant of this — and why the mock has to search both
   * scopes rather than assuming the personal one.
   */
  setShoppingItemChecked(itemId: string, checked: boolean): Promise<void> {
    for (const list of allShoppingLists()) {
      const item = list.items.find((i) => i.id === itemId);
      if (item) {
        item.checked = checked;
        break;
      }
    }
    return delay(undefined);
  },

  deleteShoppingItem(itemId: string): Promise<void> {
    for (const list of allShoppingLists()) {
      list.items = list.items.filter((i) => i.id !== itemId);
    }
    tomb('shopping_item', itemId);
    return delay(undefined);
  },

  clearCheckedShoppingItems(): Promise<{ removed: number }> {
    const removed = shoppingList.items.filter((i) => i.checked);
    shoppingList.items = shoppingList.items.filter((i) => !i.checked);
    removed.forEach((item) => tomb('shopping_item', item.id));
    return delay({ removed: removed.length });
  },

  // --------------------------------------------------------------- spaces

  listSpaces: () => delay(copy(spaces)),
  getSpace: (id) => delay(copy(requireSpace(id))),

  createSpace(name: string, type = 'general'): Promise<Space> {
    const space: Space = {
      id: nextId('sp'),
      name,
      type,
      ownerId: MOCK_USER_ID,
      myRole: 'owner',
      memberCount: 1,
      saveCount: 0,
      createdAt: now(),
      lastActivityAt: now(),
    };
    spaces.push(space);
    members[space.id] = [{ userId: MOCK_USER_ID, displayName: 'Maya', role: 'owner', joinedAt: now() }];
    activity[space.id] = [];
    return delay(copy(space));
  },

  renameSpace(id: string, name: string): Promise<Space> {
    const space = requireSpace(id);
    space.name = name;
    return delay(copy(space));
  },

  deleteSpace(id: string): Promise<void> {
    const index = spaces.findIndex((s) => s.id === id);
    if (index >= 0) spaces.splice(index, 1);
    tomb('space', id);
    return delay(undefined);
  },

  /**
   * Filtered on the save's own `spaceId`, exactly like `SaveService`'s
   * `findBySpaceId` — not a side table mapping space → save ids. A second
   * source of truth here was silently wrong in one direction: the map said a
   * save was in a Space while the save itself did not, and the local store
   * (which filters on the save) therefore showed the Space as empty.
   */
  listSpaceSaves(id: string, page = 0, size = 25): Promise<SaveResponse[]> {
    const inSpace = saves.filter((s) => s.spaceId === id);
    return delay(copy(inSpace.slice(page * size, page * size + size)));
  },

  listSpaceMembers: (id) => delay(copy(members[id] ?? [])),

  /**
   * S2, derived exactly the way `SpaceKnowledgeService.overview` derives it:
   * the same merge over the Space's saves, the same rollup rules
   * (`@/spaces/spaceProgress`, the port), the same "done means anyone" answer.
   * A hand-authored overview fixture would have proved nothing about either.
   */
  getSpaceKnowledge(id: string, comments = 8): Promise<SpaceKnowledgeOverview> {
    const facts = spaceSaveFacts(id);
    const tree = buildTree(facts);
    const allKeys = new Set<string>();
    const collect = (node: (typeof tree)[number]) => {
      node.entityKeys.forEach((key) => allKeys.add(key));
      node.subgroups.forEach(collect);
    };
    tree.forEach(collect);

    const states = spaceMemberStates(id, allKeys);
    const done = doneAcrossMembers(states);

    return delay(
      copy<SpaceKnowledgeOverview>({
        collections: tree.map((node) => withDoneCount(node, done)),
        entityCount: allKeys.size,
        doneCount: done.size,
        members: rollUpMemberProgress(states),
        recentComments: spaceComments(id, comments),
        pins: spacePins[id] ?? [],
      }),
    );
  },

  /** S1: the Space-scoped merge, plus the two things the client cannot derive. */
  listSpaceCollectionEntities(id: string, nodeId: string): Promise<SpaceEntityResponse[]> {
    const merged = mergeNode(spaceSaveFacts(id), nodeId);
    const states = spaceMemberStates(id, new Set(merged.map((e) => e.entityKey)));
    return delay(
      copy<SpaceEntityResponse[]>(
        merged.map((entity) => {
          const forEntity = states[entity.entityKey] ?? [];
          return {
            ...entity,
            sources: entity.sources.map((source) => ({
              ...source,
              addedBy: MOCK_SAVE_OWNERS[source.saveId] ?? MOCK_USER_ID,
            })),
            state: forEntity.find((m) => m.userId === MOCK_USER_ID)?.state,
            memberStates: forEntity,
            commentCount: (entityComments[id] ?? []).filter(
              (c) => c.entityKey === entity.entityKey,
            ).length,
          };
        }),
      ),
    );
  },

  // ---------------------------------------------------- S3: entity comments

  listEntityComments(id: string, entityKey: string): Promise<EntityComment[]> {
    return delay(copy((entityComments[id] ?? []).filter((c) => c.entityKey === entityKey)));
  },

  addEntityComment(id: string, entityKey: string, body: string): Promise<EntityComment> {
    const comment: EntityComment = {
      id: nextId('ec'),
      entityKey,
      userId: MOCK_USER_ID,
      displayName: 'Maya',
      body,
      createdAt: now(),
      mine: true,
    };
    entityComments[id] = [...(entityComments[id] ?? []), comment];
    return delay(copy(comment));
  },

  deleteEntityComment(id: string, commentId: string): Promise<void> {
    entityComments[id] = (entityComments[id] ?? []).filter((c) => c.id !== commentId);
    tomb('entity_comment', commentId);
    return delay(undefined);
  },

  // ------------------------------------------- S4: pins and the shared list

  listSpacePins: (id) => delay(copy(spacePins[id] ?? [])),

  /**
   * An upsert on `(kind, subject)`, exactly like the server's — which is what
   * makes a double-tapped pin one pin, here as there.
   */
  pinInSpace(
    id: string,
    kind: string,
    subject: string,
    payload: Record<string, unknown> = {},
  ): Promise<SpacePin> {
    const existing = (spacePins[id] ?? []).find((p) => p.kind === kind && p.subject === subject);
    if (existing) {
      existing.payload = payload;
      return delay(copy(existing));
    }
    const save = saves.find((s) => s.id === subject);
    const pin: SpacePin = {
      id: nextId('pin'),
      kind,
      subject,
      label:
        kind === 'save'
          ? ((save?.structuredData?.title as string | undefined) ??
            (save?.structuredData?.name as string | undefined))
          : undefined,
      payload,
      createdBy: MOCK_USER_ID,
      createdByName: 'Maya',
      // Resolved rather than assumed, like the server's join: a pin whose save
      // has left the Space still shows, marked unavailable, so an editor can
      // clear it instead of it being invisibly stuck.
      available: kind !== 'save' || save?.spaceId === id,
      createdAt: now(),
    };
    spacePins[id] = [...(spacePins[id] ?? []), pin];
    return delay(copy(pin));
  },

  unpinInSpace(id: string, pinId: string): Promise<void> {
    spacePins[id] = (spacePins[id] ?? []).filter((p) => p.id !== pinId);
    tomb('space_pin', pinId);
    return delay(undefined);
  },

  getSpaceShoppingList(id: string): Promise<ShoppingListResponse> {
    return delay(copy(spaceListFor(id)));
  },

  clearCheckedSpaceShoppingItems(id: string): Promise<{ removed: number }> {
    const list = spaceListFor(id);
    const removed = list.items.filter((i) => i.checked);
    list.items = list.items.filter((i) => !i.checked);
    removed.forEach((item) => tomb('shopping_item', item.id));
    return delay({ removed: removed.length });
  },

  setMemberRole(id: string, memberId: string, role: SpaceRole): Promise<void> {
    const member = (members[id] ?? []).find((m) => m.userId === memberId);
    if (member) member.role = role;
    return delay(undefined);
  },

  removeMember(id: string, memberId: string): Promise<void> {
    members[id] = (members[id] ?? []).filter((m) => m.userId !== memberId);
    const space = spaces.find((s) => s.id === id);
    if (space) space.memberCount = members[id].length;
    // Composite id, exactly as `TombstoneService` writes it.
    tomb('space_member', `${id}|${memberId}`);
    return delay(undefined);
  },

  createInvite(id: string, options = {}): Promise<SpaceInvite> {
    return delay<SpaceInvite>({
      id: nextId('inv'),
      // Readable rather than random: a code you can compare by eye is easier
      // to check a screenshot against.
      code: `WEAVR-${id.slice(-4).toUpperCase()}-${Math.floor(1000 + Math.random() * 8999)}`,
      role: options.role ?? 'editor',
      expiresAt: options.expiresInHours
        ? new Date(Date.now() + options.expiresInHours * 3_600_000).toISOString()
        : undefined,
      maxUses: options.maxUses,
      uses: 0,
      revoked: false,
      createdAt: now(),
    });
  },

  listInvites: () => delay<SpaceInvite[]>([]),
  revokeInvite: () => delay(undefined),

  previewInvite(code: string): Promise<InvitePreview> {
    const space = spaces[0];
    if (!code.trim()) throw new ApiError('notFound', 'That invite code is not valid.', 404);
    return delay<InvitePreview>({
      spaceId: space.id,
      spaceName: space.name,
      invitedBy: 'Sam',
      role: 'editor',
      alreadyMember: false,
    });
  },

  acceptInvite: () => delay(copy(spaces[0])),
  getSpaceActivity: (id, limit = 30) => delay(copy((activity[id] ?? []).slice(0, limit))),
  listDuplicates: () => delay<DuplicateSuggestion[]>([]),
  dismissDuplicate: () => delay(undefined),
  mergeDuplicate: () => delay(undefined),

  // ----------------------------------------------------------- discussion

  listComments: (saveId) => delay(copy(comments[saveId] ?? [])),

  addComment(saveId: string, body: string): Promise<SaveComment> {
    const comment: SaveComment = {
      id: nextId('cm'),
      userId: MOCK_USER_ID,
      displayName: 'Maya',
      body,
      createdAt: now(),
      mine: true,
    };
    comments[saveId] = [...(comments[saveId] ?? []), comment];
    return delay(copy(comment));
  },

  deleteComment(saveId: string, commentId: string): Promise<void> {
    comments[saveId] = (comments[saveId] ?? []).filter((c) => c.id !== commentId);
    tomb('comment', commentId);
    return delay(undefined);
  },

  setVote(saveId: string, value: 1 | -1 | 0): Promise<{ score: number }> {
    votes[saveId] = value;
    return delay({ score: value });
  },

  // --------------------------------------------------------------- groups

  listGroups: (): Promise<KnowledgeGroup[]> => delay(copy(MOCK_GROUPS)),

  getGroup(id: string): Promise<KnowledgeGroup> {
    const found = findGroup(MOCK_GROUPS, id);
    if (!found) throw new ApiError('notFound', 'That group could not be found.', 404);
    return delay(copy(found));
  },

  listGroupSaves(id: string, deep = false): Promise<SaveResponse[]> {
    const group = findGroup(MOCK_GROUPS, id);
    if (!group) throw new ApiError('notFound', 'That group could not be found.', 404);

    const ids = deep ? collectSaveIds(group) : group.saveIds;
    // Filtered through the live store rather than the fixture, so a save whose
    // status has changed since load shows its current state here too.
    const found = ids
      .map((saveId) => saves.find((s) => s.id === saveId))
      .filter((s): s is SaveResponse => s != null);
    return delay(copy(found));
  },

  // ----------------------------------------------------------- collections

  /**
   * Derived from `MOCK_SAVES` the same way the server derives it from
   * `saves` — `@/collections/merge` is the pure port of `CollectionService`,
   * so this exercises the real merge path (overlapping entities included)
   * rather than a hand-authored, un-mergeable fixture tree like
   * `MOCK_GROUPS`.
   */
  listCollections(): Promise<CollectionNodeResponse[]> {
    const tree = buildTree(readySaveFacts(), undefined, currentOverrides());
    const done = doneEntityKeys();
    return delay(copy(tree.map((node) => withDoneCount(node, done))));
  },

  listCollectionEntities(type: string, facet?: string): Promise<CollectionEntityResponse[]> {
    const merged = mergeType(readySaveFacts(), type, facet ?? null, currentOverrides());
    const withState = merged.map((entity) =>
      entityStates[entity.entityKey] ? { ...entity, state: entityStates[entity.entityKey] } : entity,
    );
    return delay(copy(withState));
  },

  setEntityState(entityKey: string, state: Record<string, unknown>): Promise<Record<string, unknown>> {
    // Full replace, never a merge — the same rule setSaveItemState follows.
    entityStates[entityKey] = state;
    return delay(copy(state));
  },

  // ----------------------------------------------------- collection overrides (K4c)

  mergeEntities(fromKey: string, intoKey: string): Promise<void> {
    if (fromKey === intoKey) {
      throw new ApiError('validation', 'Cannot merge an entity into itself.', 400);
    }
    // Stored raw; chains are resolved at read time by `currentOverrides`, the
    // same split `CollectionOverrideService` makes between the upsert and
    // `loadFor`'s chain-chasing.
    mergeRedirects[fromKey] = intoKey;
    return delay(undefined);
  },

  unmergeEntity(fromKey: string): Promise<void> {
    delete mergeRedirects[fromKey];
    tomb('collection_override', `entity_merge|${fromKey}`);
    return delay(undefined);
  },

  renameEntity(entityKey: string, name: string): Promise<void> {
    entityNameOverrides[entityKey] = name;
    return delay(undefined);
  },

  renameCollection(collectionId: string, name: string): Promise<void> {
    collectionNameOverrides[collectionId] = name;
    return delay(undefined);
  },

  // ------------------------------------------------------------------ sync (L4)

  /**
   * `GET /v1/sync`, as far as fixtures can honestly go.
   *
   * **Saves and deletions are genuinely windowed** — both carry a timestamp, so
   * `since` filters them exactly as the server does, and that covers the two
   * halves most likely to be wrong client-side (an upsert that should not have
   * arrived, and a deletion that should have).
   *
   * **Everything else is re-sent in full on every page**, because the fixture
   * store has no per-row change tracking to window by: `Space` carries no
   * `updatedAt` at all, and member lists, entity state and overrides are plain
   * maps. Over-delivering is safe rather than merely convenient — apply is an
   * upsert by id and the watermark advances only on success, which is the same
   * property that makes the server's own deliberate window overlap safe. It does
   * mean mock mode never exercises "this page was empty", so a bug that only
   * appears when nothing changed would not show up here.
   *
   * `hasMore` is always false: paging over fixtures would test the loop against
   * a page size nothing here comes close to.
   */
  pullSync(since?: string | null): Promise<import('@/api/types').SyncResponse> {
    const changed = (at: string) => !since || at > since;
    return delay({
      until: now(),
      hasMore: false,
      saves: copy(saves.filter((s) => changed(s.updatedAt))).map((s) => {
        // The server sends saves without `itemStates` — they travel as their own
        // list. Mirrored here so the store's strip/re-attach path is exercised
        // the same way in both modes.
        const { itemStates: _dropped, ...rest } = s;
        return rest;
      }),
      spaces: copy(spaces),
      spaceMembers: Object.entries(members).map(([spaceId, list]) => ({
        spaceId,
        members: copy(list),
      })),
      itemStates: saves.flatMap((save) =>
        Object.entries(save.itemStates ?? {}).map(([itemPath, state]) => ({
          saveId: save.id,
          itemPath,
          state: copy(state),
        })),
      ),
      entityStates: Object.entries(entityStates).map(([entityKey, state]) => ({
        entityKey,
        state: copy(state),
      })),
      overrides: [
        ...Object.entries(mergeRedirects).map(([subjectKey, into]) => ({
          overrideType: 'entity_merge',
          subjectKey,
          payload: { into } as Record<string, unknown>,
        })),
        ...Object.entries(entityNameOverrides).map(([subjectKey, name]) => ({
          overrideType: 'entity_rename',
          subjectKey,
          payload: { name } as Record<string, unknown>,
        })),
        ...Object.entries(collectionNameOverrides).map(([subjectKey, name]) => ({
          overrideType: 'collection_rename',
          subjectKey,
          payload: { name } as Record<string, unknown>,
        })),
      ],
      deleted: tombstones.filter((t) => changed(t.at)).map(({ type, id }) => ({ type, id })),
    });
  },
};

/** Depth-first by id. Recursive because the type is. */
function findGroup(groups: KnowledgeGroup[], id: string): KnowledgeGroup | undefined {
  for (const group of groups) {
    if (group.id === id) return group;
    const nested = findGroup(group.subgroups, id);
    if (nested) return nested;
  }
  return undefined;
}

/** Every save id in a subtree, this level included. */
function collectSaveIds(group: KnowledgeGroup): string[] {
  return [...group.saveIds, ...group.subgroups.flatMap(collectSaveIds)];
}

/**
 * Hand-wired stand-ins for vector search, so the `semantic` badge is reachable.
 *
 * The real thing finds "somewhere nice to eat in Denmark" → a save that says
 * Copenhagen. Reproducing that needs embeddings; reproducing the *UI state* it
 * produces needs only this.
 */
const SEMANTIC_HINTS: { term: string; saveIds: string[] }[] = [
  { term: 'japan', saveIds: ['sv-02', 'sv-06', 'sv-03'] },
  { term: 'dinner', saveIds: ['sv-03', 'sv-04'] },
  { term: 'exercise', saveIds: ['sv-01'] },
  { term: 'film', saveIds: ['sv-05'] },
];
