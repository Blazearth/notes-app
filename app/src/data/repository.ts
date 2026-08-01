/**
 * The contract every screen depends on.
 *
 * Two implementations satisfy it — `apiRepository` (the real Spring API) and
 * `mockRepository` (in-memory, no network) — and `data/index.ts` picks one from
 * `USE_MOCK_DATA`. No screen imports either implementation directly, and no
 * screen imports `@/api/client` any more, so no UI code knows or can discover
 * which side it is talking to.
 *
 * The method signatures mirror `@/api/client` one-for-one rather than being
 * redesigned around the screens. That is what keeps `ApiRepository` a pure
 * delegation with nowhere for a bug to hide, and it means the interface stays
 * an honest description of what the server actually offers — a nicer,
 * screen-shaped API would quietly become a second place where behaviour lives.
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

export interface Repository {
  // Saves
  createSave(body: CreateSaveRequest): Promise<SaveResponse>;
  listSaves(page?: number, size?: number): Promise<SaveResponse[]>;
  listSavesByLifecycle(statuses: LifecycleStatus[], size?: number): Promise<SaveResponse[]>;
  getSave(id: string): Promise<SaveResponse>;
  setSaveLifecycle(id: string, lifecycleStatus: LifecycleStatus): Promise<SaveResponse>;
  searchSaves(query: string, limit?: number): Promise<SearchHit[]>;

  // Account
  getMe(): Promise<MeResponse>;

  // The one Act
  convertToShoppingList(saveId: string): Promise<{ status: string }>;
  getShoppingList(): Promise<ShoppingListResponse>;
  setShoppingItemChecked(itemId: string, checked: boolean): Promise<void>;
  deleteShoppingItem(itemId: string): Promise<void>;
  clearCheckedShoppingItems(): Promise<{ removed: number }>;

  // Spaces
  listSpaces(): Promise<Space[]>;
  getSpace(id: string): Promise<Space>;
  createSpace(name: string, type?: string): Promise<Space>;
  renameSpace(id: string, name: string): Promise<Space>;
  deleteSpace(id: string): Promise<void>;
  listSpaceSaves(id: string, page?: number, size?: number): Promise<SaveResponse[]>;
  listSpaceMembers(id: string): Promise<SpaceMember[]>;
  setMemberRole(id: string, memberId: string, role: SpaceRole): Promise<void>;
  removeMember(id: string, memberId: string): Promise<void>;
  createInvite(
    id: string,
    options?: { role?: SpaceRole; expiresInHours?: number; maxUses?: number },
  ): Promise<SpaceInvite>;
  listInvites(id: string): Promise<SpaceInvite[]>;
  revokeInvite(id: string, inviteId: string): Promise<void>;
  previewInvite(code: string): Promise<InvitePreview>;
  acceptInvite(code: string): Promise<Space>;
  getSpaceActivity(id: string, limit?: number): Promise<ActivityEntry[]>;
  listDuplicates(id: string): Promise<DuplicateSuggestion[]>;
  dismissDuplicate(id: string, suggestionId: string): Promise<void>;
  mergeDuplicate(id: string, suggestionId: string): Promise<void>;

  // Discussion
  listComments(saveId: string): Promise<SaveComment[]>;
  addComment(saveId: string, body: string): Promise<SaveComment>;
  deleteComment(saveId: string, commentId: string): Promise<void>;
  setVote(saveId: string, value: 1 | -1 | 0): Promise<{ score: number }>;

  /** AI-derived collections. See `KnowledgeGroup` — no endpoint serves these yet. */
  listGroups(): Promise<KnowledgeGroup[]>;
}

/**
 * An AI-derived collection, shown as the Home grid and the Library grid.
 *
 * Not a wire type: no endpoint serves groups yet. It lives here rather than in
 * `@/api/types` so that the day one does, the only change is `ApiRepository`
 * gaining a real implementation — the screens already read it from the
 * repository and would not move.
 */
export interface KnowledgeGroup {
  id: string;
  /** Leading emoji, kept out of the title so the two can be laid out apart. */
  emoji: string;
  title: string;
  /** Three or so facets, joined with "•" at the call site. */
  facets: string[];
  itemCount: number;
}
