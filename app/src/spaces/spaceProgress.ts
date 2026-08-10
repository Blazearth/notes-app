/**
 * S2's shared-progress rules — the client half of `SpaceKnowledgeService`'s
 * `rollUp` / `doneKeys` ([docs/knowledge-spaces.md](../../../docs/knowledge-spaces.md)).
 *
 * Two callers, and both are the reason this is a module rather than a helper
 * inside a screen: `mockRepository` derives a real `GET /v1/spaces/{id}/knowledge`
 * response from fixtures (the same way the server derives one from
 * `entity_states`), and `SpaceDetailScreen` reads the same rules when it
 * summarises a member's line. Kept in step with the Java version by hand;
 * `SpaceKnowledgeServiceTest.java` is the source of truth if the two drift.
 *
 * Pure and dependency-free, following the `detailModel.ts` convention: it can
 * be compiled alone and executed under node, which is this app's only way to
 * run logic.
 *
 * **`done` is canonical, and that is what makes these rules two lines long.**
 * K7's three-state watchlist writes `status` *and* `done` on every transition,
 * so "has this member finished it" never has to know the status vocabulary —
 * and a row written before statuses existed still counts. The same reasoning
 * as `currentStatus`'s fallback, from the other direction.
 */

import type { SpaceMemberProgress, SpaceMemberState } from '@/api/types';

/** Every member's state for one entity, keyed by entity key — the batched read's shape. */
export type MemberStatesByEntity = Record<string, SpaceMemberState[]>;

function isDone(state: Record<string, unknown> | null | undefined): boolean {
  return state?.done === true;
}

/**
 * Entities *anyone* in the Space has finished.
 *
 * A group fact, deliberately — "3 of the 7 have been watched" is what an
 * Overview is asking, and a viewer-scoped count under a group heading reads as
 * a claim about the group (which is exactly why S0 showed no count at all).
 */
export function doneEntityKeys(states: MemberStatesByEntity): Set<string> {
  const done = new Set<string>();
  for (const [entityKey, members] of Object.entries(states)) {
    if (members.some((member) => isDone(member.state))) done.add(entityKey);
  }
  return done;
}

/**
 * "Maya: 5 watched, 2 in progress" — one row per member who has touched
 * anything, ordered by engagement.
 *
 * Members with no state at all never appear, on purpose: a roster with zeroes
 * beside everyone who has not started reads as a scoreboard nobody asked to be
 * on, where a list of who is actually participating reads as the group moving.
 *
 * Two numbers rather than one because a watchlist's interesting middle state
 * ("Watching") is invisible in a done count, and it is the state most worth
 * seeing on a shared list — it is what tells you someone is on it right now.
 */
export function rollUpMemberProgress(states: MemberStatesByEntity): SpaceMemberProgress[] {
  const byMember = new Map<string, SpaceMemberProgress>();
  for (const members of Object.values(states)) {
    for (const member of members) {
      const current = byMember.get(member.userId) ?? {
        userId: member.userId,
        displayName: member.displayName,
        doneCount: 0,
        inProgressCount: 0,
      };
      if (isDone(member.state)) current.doneCount += 1;
      else current.inProgressCount += 1;
      current.displayName = current.displayName ?? member.displayName;
      byMember.set(member.userId, current);
    }
  }
  return [...byMember.values()].sort(
    (a, b) => b.doneCount - a.doneCount || b.inProgressCount - a.inProgressCount,
  );
}

/**
 * "2 watching · 1 watched" for one entity — the group's position on one row.
 *
 * The viewer is counted in, deliberately, and only *named* attribution drops
 * them (`SpaceCollectionScreen`'s member marks, where their own control is
 * already on the row). A count that quietly meant "2 *other* people" is the
 * kind of off-by-one nobody reports and everybody misreads.
 */
export function countByDone(members: SpaceMemberState[]): { done: number; inProgress: number } {
  let done = 0;
  for (const member of members) {
    if (isDone(member.state)) done += 1;
  }
  return { done, inProgress: members.length - done };
}
