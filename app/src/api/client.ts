import { supabase } from '@/auth/supabase';
import { API_BASE_URL } from './config';
import type {
  ActivityEntry,
  CreateSaveRequest,
  DigestResponse,
  DuplicateSuggestion,
  InvitePreview,
  KnowledgeGroupResponse,
  LifecycleStatus,
  MeResponse,
  ProblemDetail,
  SaveComment,
  SaveResponse,
  SearchHit,
  ShoppingListResponse,
  Space,
  SpaceInvite,
  SpaceMember,
  SpaceRole,
} from './types';

/** Anything the UI needs to distinguish, without inspecting a status code. */
export type ApiErrorKind =
  | 'unauthorized'
  | 'forbidden'
  | 'validation'
  | 'notFound'
  | 'quota'
  | 'server'
  | 'network';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  /** Field-level messages from the validation handler, when present. */
  readonly errors: string[];
  /** Present on a `quota` error: which cap ran out, and where it stood. */
  readonly quota: { name: 'saves' | 'acts'; limit: number; used: number } | null;

  constructor(
    kind: ApiErrorKind,
    message: string,
    status: number | null,
    errors: string[] = [],
    quota: ApiError['quota'] = null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.errors = errors;
    this.quota = quota;
  }
}

/** Used when the response carries no problem body — see the 401 case below. */
const DEFAULT_MESSAGES: Record<ApiErrorKind, (status: number) => string> = {
  unauthorized: () => 'Your session has expired. Sign in again.',
  forbidden: () => "You don't have permission to do that.",
  validation: () => 'Weavr could not accept that.',
  notFound: () => 'That save no longer exists.',
  quota: () => "You've used up this plan's allowance.",
  server: (status) => `Weavr had a problem (${status}). Try again in a moment.`,
  network: () => 'Could not reach Weavr.',
};

function kindForStatus(status: number): ApiErrorKind {
  if (status === 401) return 'unauthorized';
  // 403 is deliberately NOT 'unauthorized'. Signing in again fixes a 401 and
  // does nothing for a 403 — a Space viewer trying to edit is not a stale
  // session, and telling them to re-authenticate sends them in a circle.
  if (status === 403) return 'forbidden';
  // 402 is a paywall, not a failure. Separated so the caller can show an
  // upgrade prompt rather than an error card.
  if (status === 402) return 'quota';
  if (status === 400 || status === 422) return 'validation';
  if (status === 404) return 'notFound';
  return 'server';
}

/**
 * Read the newest access token straight from the client rather than caching one.
 *
 * `getSession()` refreshes it if it has expired, which matters because a save
 * can be posted minutes after the app was last foregrounded. Holding our own
 * copy would reintroduce exactly the expiry bug the share extension has to solve
 * separately.
 */
async function authHeader(): Promise<Record<string, string>> {
  const { data, error } = await supabase.auth.getSession();
  if (error || !data.session) {
    throw new ApiError('unauthorized', 'Your session has expired. Sign in again.', null);
  }
  return { Authorization: `Bearer ${data.session.access_token}` };
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(await authHeader()),
    ...((init.headers as Record<string, string>) ?? {}),
  };

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  } catch {
    // fetch rejects only on transport failure. On a device this is almost always
    // EXPO_PUBLIC_API_BASE_URL pointing at a host the phone cannot reach.
    throw new ApiError(
      'network',
      `Could not reach the API at ${API_BASE_URL}. Check that it is running and that the URL is reachable from this device.`,
      null,
    );
  }

  if (!response.ok) {
    let problem: ProblemDetail = {};
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Verified: a 401 from the security filter chain has a *completely* empty
      // body — no content-type, zero bytes. The reason is in `WWW-Authenticate`,
      // not JSON. Without this catch, every expired token would surface as a
      // JSON parse error instead of an auth problem.
    }

    const kind = kindForStatus(response.status);
    throw new ApiError(
      kind,
      problem.detail ?? problem.title ?? DEFAULT_MESSAGES[kind](response.status),
      response.status,
      problem.errors ?? [],
      kind === 'quota' && problem.quota
        ? { name: problem.quota, limit: problem.limit ?? 0, used: problem.used ?? 0 }
        : null,
    );
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/**
 * `POST /v1/saves` → **202**, not 201: the row exists but the pipeline has not
 * run. The returned save is always `status: 'processing'`.
 *
 * The server is idempotent on a repeated `Idempotency-Key`: the same key
 * returns the existing save instead of creating a second one (see
 * `SaveService.create`). This tile doesn't send one — a duplicate tap here is
 * a fresh user action, not a retry. The header exists for the iOS share
 * extension, whose background `URLSession` retries the *same* upload attempt
 * on the OS's schedule; that extension is native code outside this app and
 * still needs to generate the key once per share and attach it to every
 * retry — nothing to plumb here until it exists.
 */
export function createSave(body: CreateSaveRequest): Promise<SaveResponse> {
  return request<SaveResponse>('/v1/saves', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** `GET /v1/saves` — newest first. Server clamps `size` to 1–100. */
export function listSaves(page = 0, size = 25): Promise<SaveResponse[]> {
  return request<SaveResponse[]>(`/v1/saves?page=${page}&size=${size}`);
}

/**
 * `GET /v1/saves?lifecycle=…` — the "Continue" rail.
 *
 * Ordered by `updatedAt` server-side, not `createdAt`: the rail is about what
 * you last touched, and a lifecycle change is exactly a touch.
 */
export function listSavesByLifecycle(
  statuses: LifecycleStatus[],
  size = 10,
): Promise<SaveResponse[]> {
  const params = new URLSearchParams({ size: String(size), lifecycle: statuses.join(',') });
  return request<SaveResponse[]>(`/v1/saves?${params.toString()}`);
}

/**
 * `GET /v1/saves/{id}` — 404 for a save belonging to someone else, but a save
 * in a Space you belong to resolves fine.
 */
export function getSave(id: string): Promise<SaveResponse> {
  return request<SaveResponse>(`/v1/saves/${id}`);
}

/**
 * `PATCH /v1/saves/{id}/lifecycle` — `saved → planned → started → completed`.
 *
 * Deliberately not a state machine server-side: going backwards is an ordinary
 * thing to want, so any transition is accepted and the returned save is
 * authoritative.
 */
export function setSaveLifecycle(
  id: string,
  lifecycleStatus: LifecycleStatus,
): Promise<SaveResponse> {
  return request<SaveResponse>(`/v1/saves/${id}/lifecycle`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ lifecycleStatus }),
  });
}

/**
 * `GET /v1/me` — entitlement and usage together.
 *
 * A limit of `-1` means unlimited (Pro, or caps not yet enforced), which is why
 * the settings screen renders a meter only when the limit is positive.
 */
export function getMe(): Promise<MeResponse> {
  return request<MeResponse>('/v1/me');
}

/**
 * `GET /v1/saves/search` — Postgres full-text and pgvector similarity, fused
 * server-side with Reciprocal Rank Fusion.
 *
 * Two consequences worth knowing at the call site:
 *
 * - **Only `ready` saves are searchable.** Nothing has been classified or
 *   embedded until the pipeline finishes, so a save created seconds ago will
 *   not appear. That is why the empty state here mentions processing rather
 *   than claiming the library is empty.
 * - **An empty array genuinely means nothing matched.** The server applies a
 *   cosine-distance cutoff precisely so a nonsense query returns nothing
 *   instead of the user's whole library ranked by noise.
 *
 * The server clamps `limit` to 1–50 and rejects a blank `q` with a 400, so the
 * caller is expected not to send one.
 */
export function searchSaves(query: string, limit = 25): Promise<SearchHit[]> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return request<SearchHit[]>(`/v1/saves/search?${params.toString()}`);
}

/**
 * `POST /v1/saves/{id}/acts/shopping-list` → **202**.
 *
 * The conversion is a queued job that spends a Gemini request, so the list is
 * *not* updated by the time this resolves — same contract as creating a save.
 * The server rejects a non-recipe or a save that is not `ready` with a 404
 * before enqueuing anything, so a 202 does mean the work was accepted.
 *
 * Safe to call twice: the job is keyed by save id, and the handler is
 * separately idempotent per (list, save) — re-converting a recipe replaces its
 * contribution rather than doubling every quantity.
 */
export function convertToShoppingList(saveId: string): Promise<{ status: string }> {
  return request<{ status: string }>(`/v1/saves/${saveId}/acts/shopping-list`, { method: 'POST' });
}

/** `GET /v1/shopping-list` — already ordered by aisle, then by name. */
export function getShoppingList(): Promise<ShoppingListResponse> {
  return request<ShoppingListResponse>('/v1/shopping-list');
}

/**
 * `GET /v1/digest` — the current week's summary, generated on demand.
 *
 * A 202 (`status: 'pending'`) is not an error: the server just enqueued the
 * Gemini call this request triggered. `request()` treats any 2xx as success,
 * so this resolves with the pending body rather than throwing.
 */
export function getDigest(): Promise<DigestResponse> {
  return request<DigestResponse>('/v1/digest');
}

export function setShoppingItemChecked(itemId: string, checked: boolean): Promise<void> {
  return request<void>(`/v1/shopping-list/items/${itemId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ checked }),
  });
}

export function deleteShoppingItem(itemId: string): Promise<void> {
  return request<void>(`/v1/shopping-list/items/${itemId}`, { method: 'DELETE' });
}

/** "I've been shopping" — drops everything ticked off, keeps the rest. */
export function clearCheckedShoppingItems(): Promise<{ removed: number }> {
  return request<{ removed: number }>('/v1/shopping-list/checked', { method: 'DELETE' });
}

// ---------------------------------------------------------------------------
// Spaces
// ---------------------------------------------------------------------------

/** `GET /v1/spaces` — every Space the caller is a member of, newest first. */
export function listSpaces(): Promise<Space[]> {
  return request<Space[]>('/v1/spaces');
}

export function getSpace(id: string): Promise<Space> {
  return request<Space>(`/v1/spaces/${id}`);
}

export function createSpace(name: string, type = 'general'): Promise<Space> {
  return request<Space>('/v1/spaces', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, type }),
  });
}

export function renameSpace(id: string, name: string): Promise<Space> {
  return request<Space>(`/v1/spaces/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
}

/** Owner only. The Space's saves return to their owners rather than being destroyed. */
export function deleteSpace(id: string): Promise<void> {
  return request<void>(`/v1/spaces/${id}`, { method: 'DELETE' });
}

/** `GET /v1/spaces/{id}/saves` — ordinary `SaveResponse` shapes, so the cards are reused. */
export function listSpaceSaves(id: string, page = 0, size = 25): Promise<SaveResponse[]> {
  return request<SaveResponse[]>(`/v1/spaces/${id}/saves?page=${page}&size=${size}`);
}

export function listSpaceMembers(id: string): Promise<SpaceMember[]> {
  return request<SpaceMember[]>(`/v1/spaces/${id}/members`);
}

export function setMemberRole(id: string, memberId: string, role: SpaceRole): Promise<void> {
  return request<void>(`/v1/spaces/${id}/members/${memberId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
}

/** Removing someone, or leaving — passing your own id is the latter. */
export function removeMember(id: string, memberId: string): Promise<void> {
  return request<void>(`/v1/spaces/${id}/members/${memberId}`, { method: 'DELETE' });
}

/**
 * `POST /v1/spaces/{id}/invites`.
 *
 * `maxUses: 1` is a one-person invite; omitting it means "anyone with the
 * link". Omitting `expiresInHours` means it never expires — worth choosing
 * deliberately, since an unbounded link posted in a group chat is exactly what
 * revocation exists for.
 */
export function createInvite(
  id: string,
  options: { role?: SpaceRole; expiresInHours?: number; maxUses?: number } = {},
): Promise<SpaceInvite> {
  return request<SpaceInvite>(`/v1/spaces/${id}/invites`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: options.role ?? 'editor', ...options }),
  });
}

export function listInvites(id: string): Promise<SpaceInvite[]> {
  return request<SpaceInvite[]>(`/v1/spaces/${id}/invites`);
}

export function revokeInvite(id: string, inviteId: string): Promise<void> {
  return request<void>(`/v1/spaces/${id}/invites/${inviteId}`, { method: 'DELETE' });
}

/** What to show before joining. 404 covers expired, revoked and used-up alike. */
export function previewInvite(code: string): Promise<InvitePreview> {
  return request<InvitePreview>(`/v1/spaces/invites/${encodeURIComponent(code)}`);
}

/** Idempotent: re-tapping your own invite returns the Space without spending a use. */
export function acceptInvite(code: string): Promise<Space> {
  return request<Space>(`/v1/spaces/invites/${encodeURIComponent(code)}/accept`, {
    method: 'POST',
  });
}

export function getSpaceActivity(id: string, limit = 30): Promise<ActivityEntry[]> {
  return request<{ activity: ActivityEntry[] }>(`/v1/spaces/${id}/activity?limit=${limit}`).then(
    (r) => r.activity,
  );
}

/** Open merge suggestions — saves in this Space that are probably the same thing. */
export function listDuplicates(id: string): Promise<DuplicateSuggestion[]> {
  return request<DuplicateSuggestion[]>(`/v1/spaces/${id}/duplicates`);
}

/** "These are not the same thing." A dismissed pair is never suggested again. */
export function dismissDuplicate(id: string, suggestionId: string): Promise<void> {
  return request<void>(`/v1/spaces/${id}/duplicates/${suggestionId}/dismiss`, { method: 'POST' });
}

/**
 * "Yes, these are the same" — the newer save leaves the Space and returns to
 * its owner's private library. Not a delete: the two saves belong to different
 * people and may carry different notes.
 */
export function mergeDuplicate(id: string, suggestionId: string): Promise<void> {
  return request<void>(`/v1/spaces/${id}/duplicates/${suggestionId}/merge`, { method: 'POST' });
}

// ---------------------------------------------------------------------------
// Comments and votes
// ---------------------------------------------------------------------------

export function listComments(saveId: string): Promise<SaveComment[]> {
  return request<SaveComment[]>(`/v1/saves/${saveId}/comments`);
}

export function addComment(saveId: string, body: string): Promise<SaveComment> {
  return request<SaveComment>(`/v1/saves/${saveId}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body }),
  });
}

export function deleteComment(saveId: string, commentId: string): Promise<void> {
  return request<void>(`/v1/saves/${saveId}/comments/${commentId}`, { method: 'DELETE' });
}

/**
 * `PUT /v1/saves/{id}/vote` — a value the user sets, not an increment.
 *
 * That makes a retried request harmless with no dedupe machinery, and it is
 * why the score is a `sum` over rows rather than a counter that a replay could
 * inflate.
 *
 * @param value 1, -1, or 0 to clear
 */
export function setVote(saveId: string, value: 1 | -1 | 0): Promise<{ score: number }> {
  return request<{ score: number }>(`/v1/saves/${saveId}/vote`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ value }),
  });
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

/**
 * `GET /v1/groups` — the library organised by what the pipeline understood.
 *
 * Derived server-side per request from each save's `knowledgeType` and its
 * extracted facets, so there is nothing to invalidate and no group ever
 * disagrees with the saves inside it. Empty until the user has `ready` saves.
 */
export function listGroups(): Promise<KnowledgeGroupResponse[]> {
  return request<KnowledgeGroupResponse[]>('/v1/groups');
}

/** `GET /v1/groups/{id}` — one node at any depth, subgroups attached. */
export function getGroup(id: string): Promise<KnowledgeGroupResponse> {
  return request<KnowledgeGroupResponse>(`/v1/groups/${encodeURIComponent(id)}`);
}

/**
 * `GET /v1/groups/{id}/saves`.
 *
 * `deep=false` (the default) is what a folder shows — the saves sitting loose
 * at this level, with subgroups listed separately above them.
 */
export function listGroupSaves(id: string, deep = false): Promise<SaveResponse[]> {
  return request<SaveResponse[]>(
    `/v1/groups/${encodeURIComponent(id)}/saves?deep=${deep ? 'true' : 'false'}`,
  );
}
