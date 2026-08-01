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
  CreateSaveRequest,
  DuplicateSuggestion,
  InvitePreview,
  LifecycleStatus,
  MeResponse,
  SaveComment,
  SaveResponse,
  SearchHit,
  ShoppingListResponse,
  Space,
  SpaceInvite,
  SpaceMember,
  SpaceRole,
} from '@/api/types';
import { ApiError } from '@/api/client';
import { MOCK_LATENCY_MS } from './config';
import {
  MOCK_ACTIVITY,
  MOCK_COMMENTS,
  MOCK_GROUPS,
  MOCK_MEMBERS,
  MOCK_SAVES,
  MOCK_SHOPPING_LIST,
  MOCK_SPACES,
  MOCK_USER_ID,
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

let idCounter = 100;
const nextId = (prefix: string) => `${prefix}-${++idCounter}`;
const now = () => new Date().toISOString();

// Mutable stores, seeded from the fixtures.
const saves: SaveResponse[] = copy(MOCK_SAVES);
const spaces: Space[] = copy(MOCK_SPACES);
const members: Record<string, SpaceMember[]> = copy(MOCK_MEMBERS);
const activity: Record<string, ActivityEntry[]> = copy(MOCK_ACTIVITY);
const comments: Record<string, SaveComment[]> = copy(MOCK_COMMENTS);
const shoppingList: ShoppingListResponse = copy(MOCK_SHOPPING_LIST);
const votes: Record<string, number> = {};
const spaceSaves: Record<string, string[]> = { 'sp-japan': ['sv-02', 'sv-06'], 'sp-book': ['sv-10'] };

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

  createSave(body: CreateSaveRequest): Promise<SaveResponse> {
    const save: SaveResponse = {
      id: nextId('sv'),
      sourceType: body.sourceType,
      sourceUrl: body.sourceUrl,
      spaceId: body.spaceId,
      // `processing`, like the real thing: a save is never ready on creation,
      // and a mock that returns `ready` would hide the entire status UI.
      status: 'processing',
      createdAt: now(),
      updatedAt: now(),
    };
    saves.unshift(save);

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

  // ------------------------------------------------------------ the Act

  convertToShoppingList(saveId: string): Promise<{ status: string }> {
    const save = requireSave(saveId);
    const ingredients = Array.isArray(save.structuredData?.ingredients)
      ? (save.structuredData.ingredients as string[])
      : [];

    // Contribution-per-save, exactly as the server folds it: re-converting the
    // same recipe replaces its lines instead of doubling every quantity.
    for (const item of shoppingList.items) {
      item.sources = item.sources.filter((s) => s !== saveId);
    }
    let index = 0;
    for (const line of ingredients) {
      const existing = shoppingList.items.find((i) => line.toLowerCase().includes(i.name.toLowerCase()));
      if (existing) {
        if (!existing.sources.includes(saveId)) existing.sources.push(saveId);
      } else {
        shoppingList.items.push({
          id: nextId('it'),
          name: line,
          category: shoppingList.categories[index % shoppingList.categories.length],
          checked: false,
          sources: [saveId],
        });
      }
      index += 1;
    }
    shoppingList.items = shoppingList.items.filter((i) => i.sources.length > 0);
    return delay({ status: 'accepted' });
  },

  getShoppingList(): Promise<ShoppingListResponse> {
    return delay(copy(shoppingList));
  },

  setShoppingItemChecked(itemId: string, checked: boolean): Promise<void> {
    const item = shoppingList.items.find((i) => i.id === itemId);
    if (item) item.checked = checked;
    return delay(undefined);
  },

  deleteShoppingItem(itemId: string): Promise<void> {
    shoppingList.items = shoppingList.items.filter((i) => i.id !== itemId);
    return delay(undefined);
  },

  clearCheckedShoppingItems(): Promise<{ removed: number }> {
    const before = shoppingList.items.length;
    shoppingList.items = shoppingList.items.filter((i) => !i.checked);
    return delay({ removed: before - shoppingList.items.length });
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
    };
    spaces.push(space);
    members[space.id] = [{ userId: MOCK_USER_ID, displayName: 'Maya', role: 'owner', joinedAt: now() }];
    activity[space.id] = [];
    spaceSaves[space.id] = [];
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
    return delay(undefined);
  },

  listSpaceSaves(id: string, page = 0, size = 25): Promise<SaveResponse[]> {
    const ids = spaceSaves[id] ?? [];
    const inSpace = saves.filter((s) => ids.includes(s.id));
    return delay(copy(inSpace.slice(page * size, page * size + size)));
  },

  listSpaceMembers: (id) => delay(copy(members[id] ?? [])),

  setMemberRole(id: string, memberId: string, role: SpaceRole): Promise<void> {
    const member = (members[id] ?? []).find((m) => m.userId === memberId);
    if (member) member.role = role;
    return delay(undefined);
  },

  removeMember(id: string, memberId: string): Promise<void> {
    members[id] = (members[id] ?? []).filter((m) => m.userId !== memberId);
    const space = spaces.find((s) => s.id === id);
    if (space) space.memberCount = members[id].length;
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
    return delay(undefined);
  },

  setVote(saveId: string, value: 1 | -1 | 0): Promise<{ score: number }> {
    votes[saveId] = value;
    return delay({ score: value });
  },

  // --------------------------------------------------------------- groups

  listGroups: (): Promise<KnowledgeGroup[]> => delay(copy(MOCK_GROUPS)),
};

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
