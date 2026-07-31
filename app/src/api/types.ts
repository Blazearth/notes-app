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
  /** Optional target space; omit for the user's private feed. */
  spaceId?: string;
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
  status: SaveStatus;
  knowledgeType?: string;
  confidence?: number;
  structuredData?: Record<string, unknown>;
  lifecycleStatus?: LifecycleStatus;
  modelUsed?: string;
  errorCode?: string;
  errorMessage?: string;
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

/** RFC 9457 problem details, as produced by `ApiExceptionHandler`. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Added by the validation handler only. */
  errors?: string[];
}
