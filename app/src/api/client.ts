import { supabase } from '@/auth/supabase';
import { API_BASE_URL } from './config';
import type { CreateSaveRequest, ProblemDetail, SaveResponse, SearchHit } from './types';

/** Anything the UI needs to distinguish, without inspecting a status code. */
export type ApiErrorKind = 'unauthorized' | 'validation' | 'notFound' | 'server' | 'network';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  /** Field-level messages from the validation handler, when present. */
  readonly errors: string[];

  constructor(kind: ApiErrorKind, message: string, status: number | null, errors: string[] = []) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.errors = errors;
  }
}

/** Used when the response carries no problem body — see the 401 case below. */
const DEFAULT_MESSAGES: Record<ApiErrorKind, (status: number) => string> = {
  unauthorized: () => 'Your session has expired. Sign in again.',
  validation: () => 'Weavr could not accept that.',
  notFound: () => 'That save no longer exists.',
  server: (status) => `Weavr had a problem (${status}). Try again in a moment.`,
  network: () => 'Could not reach Weavr.',
};

function kindForStatus(status: number): ApiErrorKind {
  if (status === 401 || status === 403) return 'unauthorized';
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

/** `GET /v1/saves/{id}` — 404 for a save belonging to someone else. */
export function getSave(id: string): Promise<SaveResponse> {
  return request<SaveResponse>(`/v1/saves/${id}`);
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
