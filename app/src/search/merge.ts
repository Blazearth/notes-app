/**
 * Fusing the two halves of search — the device's index and the server's.
 *
 * Local FTS answers in a millisecond and can answer with no network at all;
 * `GET /v1/saves/search` answers in a round trip and knows things the device
 * cannot: it fuses full-text with pgvector by RRF, so it finds a save whose
 * words the user never typed ("somewhere nice to eat in Denmark" → a save that
 * says Copenhagen and never says Denmark), and its full-text half indexes
 * `raw_caption`, which `SaveResponse` does not carry.
 *
 * Neither replaces the other, so the screen shows the local list immediately and
 * merges the server's in when it lands.
 *
 * **This file has no runtime imports**, deliberately and for the same reason
 * `outbox.ts` has none: it can then be compiled alone and *executed* under node
 * rather than only typechecked (see `docs/testing.md`).
 */

import type { SaveResponse, SearchHit } from '@/api/types';

/** Local hits are, by construction, text matches — that is what the index is. */
export function toLocalHits(saves: readonly SaveResponse[]): SearchHit[] {
  return saves.map((save) => ({ save, match: 'text' }));
}

/**
 * One list from the two.
 *
 * Three rules, and the order of them is the design:
 *
 * 1. **The server's ranking wins wherever the two overlap.** It has both halves
 *    of hybrid search and the full indexed text; the local index is a subset of
 *    what it knows. So the server's list is taken whole, in its own order, with
 *    its own `match` label — which is also what keeps the "related" badge
 *    correct, since only the server can tell a semantic-only hit from a textual
 *    one.
 * 2. **Local-only hits are appended, never dropped.** They are the saves the
 *    server did not return: one embedded moments ago, one whose text matched a
 *    prefix the server's `tsquery` did not extend, or — the case that matters —
 *    every hit there is, because the request failed. Dropping them would make
 *    the arrival of a server response *remove* results the user could already
 *    see.
 * 3. **`server === null` means "no answer yet, or no answer at all"**, and the
 *    local list stands alone. There is deliberately no distinction here between
 *    in-flight and failed: both mean the same thing to the merge, and the screen
 *    is where the difference is worth showing.
 */
export function mergeSearchHits(
  local: readonly SearchHit[],
  server: readonly SearchHit[] | null,
): SearchHit[] {
  if (server === null) return [...local];
  const seen = new Set(server.map((hit) => hit.save.id));
  return [...server, ...local.filter((hit) => !seen.has(hit.save.id))];
}
