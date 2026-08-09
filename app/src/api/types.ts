/**
 * Wire types, mirroring the Java DTOs one-for-one.
 *
 * The enums are **lower-case on the wire**, not the Java constant names: every
 * one implements `DbEnum`, whose `db()` carries `@JsonValue`. Getting this wrong
 * fails at the `@JsonCreator` on the way in, which reads as a 400 with no
 * obvious cause.
 *
 * Source: `api/src/main/java/com/weavr/api/save/`.
 */

/** `SourceType` — what the client handed over, not what it turns out to be. */
export type SourceType = 'url' | 'text' | 'image' | 'pdf' | 'audio';

/** `SaveStatus`. `pending` is a parked save, not a failure — see below. */
export type SaveStatus = 'processing' | 'pending' | 'ready' | 'failed';

/** `LifecycleStatus` — how far the user has got with a save. */
export type LifecycleStatus = 'saved' | 'planned' | 'started' | 'completed';

/** `CreateSaveRequest`. Deliberately tiny: this must never carry media. */
export interface CreateSaveRequest {
  sourceType: SourceType;
  /** Required when `sourceType` is `url`. Max 2048 chars server-side. */
  sourceUrl?: string;
  /** Required when `sourceType` is `text`. Max 100,000 chars server-side. */
  text?: string;
  /** Optional title for text notes. Max 500 chars. */
  title?: string;
  /** Optional target space; omit for the user's private feed. */
  spaceId?: string;
}

/** Body for `PATCH /v1/saves/{id}/note` — edits a text note's title and body. */
export interface UpdateNoteRequest {
  title?: string;
  body?: string;
}

/**
 * `SaveResponse`. Serialised with `@JsonInclude(NON_NULL)`, so every field the
 * pipeline has not filled in yet is *absent* rather than null — hence the
 * optionals. Only `id`, `sourceType`, `status` and the timestamps are dependable
 * on a freshly created save.
 */
export interface SaveResponse {
  id: string;
  spaceId?: string;
  sourceType: SourceType;
  sourceUrl?: string;
  /** Raw text the user typed (or on-device OCR for images). Present only for `text` / `image` sourceTypes. */
  rawCaption?: string;
  status: SaveStatus;
  knowledgeType?: string;
  confidence?: number;
  structuredData?: Record<string, unknown>;
  lifecycleStatus?: LifecycleStatus;
  modelUsed?: string;
  thumbnailUrl?: string;
  favorite: boolean;
  archived: boolean;
  errorCode?: string;
  errorMessage?: string;
  /**
   * The caller's own Phase 4 object-behavior state, keyed by `itemPath`
   * (`"exercises[2]"`, `""` for whole-save state) — absent rather than `{}`
   * when nothing has been touched yet. See `SaveItemStateService` server-side
   * and `docs/next-phases.md` §4.1.
   */
  itemStates?: Record<string, Record<string, unknown>>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Which half of hybrid search found a result — `SearchController.label`.
 *
 * Exposed by the server so the escalation-rate question ("is the vector half
 * contributing anything?") is answerable without a database session. The UI
 * shows it only for `semantic`, where it explains a result whose words the user
 * never typed.
 */
export type SearchMatch = 'both' | 'text' | 'semantic';

/** `SearchController.SearchHit` — a save plus how it was found. */
export interface SearchHit {
  save: SaveResponse;
  match: SearchMatch;
}

/** One line on the shopping list — `ShoppingListController.ItemResponse`. */
export interface ShoppingListItem {
  id: string;
  name: string;
  /** Text, not a number: recipes say "a pinch" and "2-3" as often as "400". */
  quantity?: string;
  unit?: string;
  category: string;
  checked: boolean;
  /** Every save that contributed, so "why is this here?" is answerable. */
  sources: string[];
}

/**
 * `ShoppingListController.ShoppingListResponse`.
 *
 * `categories` is the aisle order the server wants rendered. It ships with the
 * payload rather than being duplicated client-side precisely so a new aisle is
 * a prompt change on the server and nothing else — the same reason
 * `knowledgeType` is free text.
 */
export interface ShoppingListResponse {
  /** Absent until the user's first conversion — an empty list is not a 404. */
  id?: string;
  items: ShoppingListItem[];
  categories: string[];
}

/**
 * `DigestController.DigestResponse`.
 *
 * `status` is the whole story, not a side channel: `'ready'` has `summary`
 * populated, `'pending'` means the server just enqueued a Gemini call and the
 * screen should poll or wait for a push, `'empty'` means the week genuinely
 * had nothing saved — three different reasons to show nothing, and the caller
 * should not have to guess which one a missing `summary` means.
 */
export interface DigestResponse {
  summary: string | null;
  saveCount: number;
  weekStart: string;
  status: 'ready' | 'pending' | 'empty';
}

/**
 * `SpaceRole` — a three-value enum, not capability booleans.
 *
 * Ordered: `owner` > `editor` > `viewer`. A viewer can read and comment; adding
 * content needs editor; deleting the Space needs owner.
 */
export type SpaceRole = 'owner' | 'editor' | 'viewer';

/** `SpaceService.Space`. `myRole` is the caller's, not the owner's. */
export interface Space {
  id: string;
  name: string;
  type: string;
  ownerId: string;
  myRole: SpaceRole;
  memberCount: number;
  saveCount: number;
  createdAt: string;
  /** Most recent `space_activity` row — `space_created`, `member_joined`, `save_added`, etc. Falls back to `createdAt` when nothing has happened since. */
  lastActivityAt: string;
}

export interface SpaceMember {
  userId: string;
  displayName?: string;
  role: SpaceRole;
  joinedAt: string;
}

/**
 * `SpaceService.Invite`. The server returns a bare `code`, not a URL — the
 * deep-link scheme is the client's business, and the QR is generated on-device
 * from whatever link this app decides to build.
 */
export interface SpaceInvite {
  id: string;
  code: string;
  role: SpaceRole;
  /** Absent means the link never expires. */
  expiresAt?: string;
  /** Absent means "anyone with the link". */
  maxUses?: number;
  uses: number;
  revoked: boolean;
  createdAt: string;
}

/** What the join screen shows before the user commits. */
export interface InvitePreview {
  spaceId: string;
  spaceName: string;
  invitedBy: string;
  role: SpaceRole;
  alreadyMember: boolean;
}

/**
 * `SpaceService.ActivityEntry`. Sparse by design — joins, saves, completions,
 * comments and votes only, never a per-scroll signal.
 */
export interface ActivityEntry {
  id: string;
  userId: string;
  displayName: string;
  saveId?: string;
  saveTitle?: string;
  type: string;
  createdAt: string;
}

/**
 * `DuplicateDetector.Suggestion` — two saves in a Space that are probably the
 * same thing, found by embedding distance rather than by URL.
 *
 * Always a suggestion: the server never merges on its own.
 */
export interface DuplicateSuggestion {
  id: string;
  saveId: string;
  saveTitle?: string;
  duplicateOf: string;
  duplicateTitle?: string;
  distance: number;
  status: string;
}

/** `SaveSocialService.Comment`. `mine` decides whether a delete affordance shows. */
export interface SaveComment {
  id: string;
  userId: string;
  displayName: string;
  body: string;
  createdAt: string;
  mine: boolean;
}

/**
 * `MeController.MeResponse` — entitlement and usage in one call.
 *
 * Entitlement comes from the server rather than the RevenueCat SDK because the
 * two can disagree, and the server's answer is the one that governs what
 * actually happens. A limit of `-1` means unlimited: either the user is Pro, or
 * caps are not being enforced yet.
 */
export interface MeResponse {
  userId: string;
  pro: boolean;
  entitlement?: string;
  subscriptionStatus?: string;
  renewsAt?: string;
  savesUsed: number;
  savesLimit: number;
  actsUsed: number;
  actsLimit: number;
}

/** RFC 9457 problem details, as produced by `ApiExceptionHandler`. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Added by the validation handler only. */
  errors?: string[];
  /** Added by the 402 handler: which cap ran out, and where it stood. */
  quota?: 'saves' | 'acts';
  limit?: number;
  used?: number;
}

/**
 * `GroupController.GroupNode` — a node in the AI group tree.
 *
 * Recursive on purpose: a subgroup is the same shape as its parent, so the
 * hierarchy has no built-in depth limit and one screen renders every level.
 * The server derives the whole tree per request from `knowledgeType` plus the
 * facets the classify call already extracted, so it costs no Gemini request.
 */
export interface KnowledgeGroupResponse {
  id: string;
  name: string;
  description?: string;
  /** The whole subtree, not `saveIds.length`. */
  itemCount: number;
  subgroups: KnowledgeGroupResponse[];
  saveIds: string[];
}

/**
 * `CollectionController.CollectionNode` — a node in the collection tree,
 * `KnowledgeGroupResponse`'s counterpart one level further merged: a leaf
 * holds distinct entity keys instead of save ids.
 *
 * Recursive on purpose, same reasoning as groups. Derived server-side per
 * request from each save's `knowledgeType` and its extracted items, so it
 * costs no Gemini request and cannot disagree with the saves inside it.
 */
export interface CollectionNodeResponse {
  id: string;
  name: string;
  description?: string;
  /** The whole subtree, not `entityKeys.length`. */
  entityCount: number;
  /** The whole subtree's entities with K2 state `done: true` — 0 until any entity in it is marked done. */
  doneCount: number;
  /** The whole subtree's distinct saves feeding it. */
  sourceCount: number;
  subgroups: CollectionNodeResponse[];
  entityKeys: string[];
  saveIds: string[];
}

/** `CollectionEntity.Source` — one source's own, un-merged copy of an item. */
export interface CollectionSource {
  saveId: string;
  savedAt: string;
  item: Record<string, unknown>;
}

/**
 * `CollectionController.CollectionEntity` — a merged entity: a thing the
 * user is collecting, appearing in one or more sources. See
 * `docs/knowledge-collections.md` ("What merging produces per entity").
 */
export interface CollectionEntityResponse {
  entityKey: string;
  name: string;
  kind: string;
  /** Every other item field, rolled up: a list unions across sources, a scalar takes the first non-`[unclear]` value. */
  fields: Record<string, unknown>;
  /** Each source's own, un-merged copy of the item — `reason` from one save is never blended with another's. */
  sources: CollectionSource[];
  /** Distinct saves this entity appears in — "recommended in 3 saves," literally. */
  sourceCount: number;
  /** The caller's own K2 entity state (`done`, `rating`, …) — absent when never touched. */
  state?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Sync (L4)
// ---------------------------------------------------------------------------

/**
 * `SyncResponse.Deletion` — the only thing a delta cannot infer.
 *
 * `type` is one of `TombstoneService`'s constants (`space`, `space_member`,
 * `comment`, `vote`, `shopping_item`, `collection_override`). Two of them carry
 * a composite `id` joined by `|`: `space_member` is `"<spaceId>|<userId>"` and
 * `collection_override` is `"<overrideType>|<subjectKey>"`.
 *
 * The client applies the types it caches and ignores the rest, deliberately: a
 * server that starts tombstoning something new must not break an older client,
 * and `comment`/`vote` are written for completeness (the deletion record is
 * either whole or it is a thing nobody can reason about) while nothing local
 * holds either.
 */
export interface SyncDeletion {
  type: string;
  id: string;
}

/** `SyncResponse` — one page of `GET /v1/sync`. Every list is "what changed". */
export interface SyncResponse {
  /**
   * The cursor to send back as `since`. Always from the server's own clock via
   * the rows it selected — the client never formats a timestamp of its own,
   * because device clock skew against Postgres is otherwise silent data loss.
   */
  until: string;
  /** Another page is waiting. A client loops, applying and advancing each time. */
  hasMore: boolean;
  /**
   * The caller's own saves. Deliberately without `itemStates` — those arrive as
   * their own list, and the store holds them in a separate table for exactly
   * this reason.
   */
  saves: SaveResponse[];
  spaces: Space[];
  /** The **whole** member list of any Space where any member row changed. */
  spaceMembers: { spaceId: string; members: SpaceMember[] }[];
  itemStates: { saveId: string; itemPath: string; state: Record<string, unknown> }[];
  entityStates: { entityKey: string; state: Record<string, unknown> }[];
  overrides: { overrideType: string; subjectKey: string; payload: Record<string, unknown> }[];
  deleted: SyncDeletion[];
}
