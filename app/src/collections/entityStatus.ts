/**
 * Where an entity has got to — the three-state watchlist, and the rule that
 * keeps it honest against the `done` flag everything else already counts.
 *
 * K2 stored one boolean (`state.done`) and K3 sectioned on it, which is right
 * for a checklist and thin for a watchlist: "want to watch" and "watching"
 * are the same value of `done`, and collapsing them loses the only
 * distinction a watchlist exists to make.
 *
 * **`done` stays canonical.** A status does not replace it, it refines it —
 * every status declares whether it *implies* done, and writing a status
 * always writes both. That is what keeps `CollectionNode.doneCount` (server
 * and client), the Library's "1 watched" line, and `SaveDetailScreen`'s
 * dual-read of the same entity working with no change at all, and it is why
 * this needed no migration: `entity_states.state` is jsonb written by full
 * replace, so a new key is additive by construction.
 *
 * Pure and store-free, so the rules below can be executed under node against
 * fixtures — this repo's standing verification technique.
 */

export interface EntityStatus {
  /** Stored in `state.status`. Stable — it is data, not a label. */
  key: string;
  /** Shown on the pill and as the section heading. */
  label: string;
  /** Whether being in this status means the entity is done. */
  done: boolean;
}

/**
 * Statuses by knowledge type. A type absent here has no status model and
 * stays a plain done/not-done toggle — right for a checklist, whose items
 * genuinely have two states, and for an itinerary's places, where "currently
 * visiting Kyoto" is not a thing anyone tracks in an app.
 */
const STATUSES: Record<string, EntityStatus[]> = {
  recommendation_list: [
    { key: 'want', label: 'Want to watch', done: false },
    { key: 'watching', label: 'Watching', done: false },
    { key: 'watched', label: 'Watched', done: true },
  ],
};

export function statusesFor(type: string): EntityStatus[] | null {
  return STATUSES[type] ?? null;
}

/**
 * The status an entity is in.
 *
 * Falls back to `done` when `state.status` is absent — which is every entity
 * marked watched before this existed, and every one written by
 * `SaveDetailScreen`'s simpler item-level control. Without that fallback a
 * previously-watched title would quietly reappear under "Want to watch",
 * which is the same class of silent data loss as an optimistic revert.
 */
export function currentStatus(type: string, state?: Record<string, unknown> | null): EntityStatus | null {
  const statuses = statusesFor(type);
  if (!statuses) return null;

  const stored = state?.status;
  if (typeof stored === 'string') {
    const match = statuses.find((s) => s.key === stored);
    if (match) return match;
  }
  // No stored status: read it off `done`. The last done-implying status is
  // the terminal one, which is what a bare `done: true` meant.
  const done = state?.done === true;
  return done ? [...statuses].reverse().find((s) => s.done) ?? statuses[0] : statuses[0];
}

/** The next status in the cycle — what tapping the pill does. */
export function nextStatus(type: string, state?: Record<string, unknown> | null): EntityStatus | null {
  const statuses = statusesFor(type);
  const current = currentStatus(type, state);
  if (!statuses || !current) return null;
  return statuses[(statuses.indexOf(current) + 1) % statuses.length];
}

/**
 * The full state to write for a status.
 *
 * Both keys, always: `status` for the section it belongs in, `done` for every
 * consumer that predates statuses. The write is a full replace everywhere it
 * lands, so returning a whole object rather than a patch is the shape the
 * store and the endpoint both want.
 */
export function stateForStatus(
  state: Record<string, unknown> | null | undefined,
  status: EntityStatus,
): Record<string, unknown> {
  return { ...state, status: status.key, done: status.done };
}

/** Groups entities into their status sections, in the type's own order, dropping empty ones. */
export function bySection<T extends { state?: Record<string, unknown> }>(
  type: string,
  entities: T[],
): { status: EntityStatus; entities: T[] }[] {
  const statuses = statusesFor(type);
  if (!statuses) return [];
  return statuses
    .map((status) => ({
      status,
      entities: entities.filter((e) => currentStatus(type, e.state)?.key === status.key),
    }))
    .filter((section) => section.entities.length > 0);
}
