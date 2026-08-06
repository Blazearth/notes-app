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
  DigestResponse,
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
  /** Swipe-to-favorite / swipe-to-archive. Either field may be omitted to leave it as-is. */
  setSaveFlags(id: string, flags: { favorite?: boolean; archived?: boolean }): Promise<SaveResponse>;
  searchSaves(query: string, limit?: number): Promise<SearchHit[]>;

  // Account
  getMe(): Promise<MeResponse>;

  /**
   * The current week's summary. `status: 'pending'` means the server just
   * enqueued generation — call again shortly rather than treating it as done.
   */
  getWeeklyDigest(): Promise<DigestResponse>;

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

  /** Top-level groups. See `KnowledgeGroup` — no endpoint serves these yet. */
  listGroups(): Promise<KnowledgeGroup[]>;
  /** One group by id, at any depth, with its subgroups attached. */
  getGroup(id: string): Promise<KnowledgeGroup>;
  /**
   * The saves in a group.
   *
   * `deep: false` is what a folder shows — the items at this level, with
   * subgroups listed separately above them. `deep: true` collects the whole
   * subtree, which is what a search or a count over the group needs.
   */
  listGroupSaves(id: string, deep?: boolean): Promise<SaveResponse[]>;
}

/**
 * An AI-derived collection — an intelligent folder, not a category.
 *
 * **The type is recursive, and that is the point.** A group holds subgroups of
 * exactly its own shape, so nesting is unbounded by construction rather than by
 * a `subgroups: Subgroup[]` field that would cap the hierarchy at two levels
 * and need a schema change plus a screen rewrite to go deeper. Drag-and-drop
 * reparenting, AI-suggested subgroups and nested filtering all become changes
 * to *data* rather than to this shape.
 *
 * The screens follow from that: one detail screen renders a group and its
 * children, and navigating into a child re-renders the same screen with a
 * different id. Arbitrary depth costs no extra UI.
 *
 * Not a wire type — no endpoint serves groups yet. It lives here rather than in
 * `@/api/types` so that the day one does, the only change is `ApiRepository`
 * gaining a real implementation; the screens already read it from the
 * repository and would not move.
 */
export interface KnowledgeGroup {
  id: string;
  name: string;
  /** Optional prose. The card shows the subgroup names instead — those say what is actually inside. */
  description?: string;
  /**
   * Everything beneath this node, its subgroups included — not the length of
   * `saveIds`. A folder showing the count of what happens to sit loose at its
   * own level, while its children hold hundreds, is worse than showing nothing.
   */
  itemCount: number;
  /** Reserved for the visual design system. Deliberately unused for now. */
  illustration?: string;
  coverImage?: string;
  /** Empty for a leaf. Never undefined, so callers never branch on absence. */
  subgroups: KnowledgeGroup[];
  /** Saves held directly at this level, as ids — the store owns the saves. */
  saveIds: string[];
}
