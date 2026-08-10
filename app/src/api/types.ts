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
  /**
   * The save's owner UUID. Present in the JSON stored locally so the
   * personal feed (Library / Home) can exclude space-mates' saves.
   */
  userId?: string;
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
  /** Unique username chosen by the user. Null if not yet set. */
  username?: string | null;
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
  /**
   * Who saved it (S1). Absent on every personal read, where the answer is
   * always the caller — only a Space-scoped merge attributes sources, and it
   * is the one thing on this shape the client cannot derive, because a save's
   * owner is not on `SaveResponse`.
   */
  addedBy?: string;
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
// Knowledge-first Spaces (S1, S2)
// ---------------------------------------------------------------------------

/**
 * `SpaceKnowledgeService.MemberState` — one member's state for one entity.
 *
 * The whole `state` object, not a flag: K7's `status`
 * (`want`/`watching`/`watched`) and a `rating` both ride here, and `done`
 * stayed canonical so a reader that only knows the boolean is still right.
 *
 * **These are global states, deliberately.** `entity_states` is keyed
 * `(user_id, entity_key)` with no Space dimension — completing Your Name is a
 * fact about Rahul, not about any one Space — so a member's status shows in
 * every Space whose knowledge contains that entity. The People tab says so.
 */
export interface SpaceMemberState {
  userId: string;
  displayName?: string;
  state: Record<string, unknown>;
}

/** `SpaceKnowledgeService.MemberProgress` — "Maya: 5 watched, 2 in progress". */
export interface SpaceMemberProgress {
  userId: string;
  displayName?: string;
  doneCount: number;
  /** Entities they have a state for that isn't done — K7's "Watching". */
  inProgressCount: number;
}

/**
 * `SpaceKnowledgeService.SpaceComment` — one remark in the Space, from either
 * place discussion can attach.
 *
 * Exactly one of `saveId` and `entityKey` is present. S2 had only the first (a
 * comment on one of the Space's saves); S3 added the second, a comment on a
 * merged entity, which is where "Blue Box starts slow" actually belongs. They
 * share one block because "what's being talked about in here" is one question,
 * and splitting it by which table a remark landed in would ask the reader to
 * care about a storage detail.
 */
export interface SpaceCommentEntry {
  id: string;
  saveId?: string;
  saveTitle?: string;
  /** S3. Set instead of `saveId` when the remark is about a merged entity. */
  entityKey?: string;
  /** The entity's display name, resolved server-side — an entity key is casefolded and not readable. */
  entityName?: string;
  userId: string;
  displayName: string;
  body: string;
  createdAt: string;
}

/**
 * `EntityCommentService.EntityComment` — S3, one message in an entity's thread.
 *
 * **Space-scoped, unlike `entity_states`.** A status is a fact about a person
 * and shows in every Space containing that entity (see `SpaceMemberState`); a
 * remark was said in a room and stays in it. Same-looking key, deliberately
 * different storage.
 */
export interface EntityComment {
  id: string;
  entityKey: string;
  userId: string;
  displayName: string;
  body: string;
  createdAt: string;
  /** Whether the caller wrote it — what decides if a delete affordance shows. */
  mine: boolean;
}

/**
 * `SpacePinService.Pin` — S4, what a person in this Space chose to put at the
 * top.
 *
 * The honest version of the vision's "Current Program": a member pointed at one
 * save, rather than the model assembling a routine out of several (which is the
 * synthesis `docs/knowledge-collections.md` rules out, and whose failure mode is
 * a program in a gym that no human wrote).
 */
export interface SpacePin {
  id: string;
  /** `save` or `collection`. Free text server-side, so a new kind is never a migration. */
  kind: string;
  /** A save id, or a collection node id (`recommendation_list~anime`). */
  subject: string;
  /** Resolved on read from the save's own data, never stored — a copied label goes stale on the first rename. */
  label?: string;
  /** The kind's own detail. A save pin's `{ date }` is how "Saturday: Lasagna" exists with no calendar feature. */
  payload: Record<string, unknown>;
  createdBy: string;
  createdByName: string;
  /** False once the pinned save has left the Space or been deleted — shown so an editor can clear it. */
  available: boolean;
  createdAt: string;
}

/**
 * `GET /v1/spaces/{id}/knowledge` — what the Overview cannot derive locally.
 *
 * `collections` rides along so a cold visit is one request, but the client
 * already derives the identical tree from the Space's saves (S0), which is why
 * this response failing leaves the Overview standing rather than empty.
 * `doneCount` here means "entities **anyone** in the Space finished" — a group
 * fact, unlike the same field on a personal collection node.
 */
export interface SpaceKnowledgeOverview {
  collections: CollectionNodeResponse[];
  entityCount: number;
  doneCount: number;
  members: SpaceMemberProgress[];
  recentComments: SpaceCommentEntry[];
  /** S4. Rides along so the Overview stays one request, same as `collections`. */
  pins: SpacePin[];
}

/**
 * `SpaceKnowledgeService.SpaceEntity` — a merged entity as a Space sees it:
 * `CollectionEntityResponse`'s fields plus everyone else's state.
 *
 * `state` is the viewer's own (so a control reads where it writes);
 * `memberStates` is every member's, the viewer included — filtering them out
 * would make "2 people watched this" quietly mean "2 *other* people".
 */
export interface SpaceEntityResponse extends CollectionEntityResponse {
  memberStates: SpaceMemberState[];
  /**
   * S3's thread length, batched into this response rather than fetched per row
   * — a list needs a count ("2 comments"), and the thread itself is one tap and
   * one request away.
   */
  commentCount: number;
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
