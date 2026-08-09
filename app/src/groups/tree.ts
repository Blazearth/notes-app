/**
 * Pure port of `GroupService`'s tree core (`api/.../group/GroupService.java`)
 * and `GroupNode.of`.
 *
 * **Why the client derives this rather than fetching it.** `GET /v1/groups`
 * loads the user's entire `ready` library as JPA entities and rebuilds the
 * whole tree per request — it is a *view* over saves, not stored state. Once
 * the local store holds the whole library (see `@/local`), the same view can be
 * computed here for free, instantly, and offline. The endpoint stays in the
 * `Repository` interface and keeps `mockRepository` parity honest; the app just
 * stops calling it.
 *
 * Kept in step with the Java version by hand, exactly like
 * `@/collections/merge` — `GroupServiceTest.java` is the source of truth for
 * grouping behaviour if the two ever drift. Four things in here are
 * load-bearing and must not be "tidied":
 *
 * | Thing            | Value                  | Why                                                                   |
 * |------------------|------------------------|-----------------------------------------------------------------------|
 * | `MIN_GROUP_SIZE` | `5`                    | A subgroup of 1 is noise; changing it reshapes the Library            |
 * | `ID_SEPARATOR`   | `~`                    | Group ids are **route params** (`/group/[id]`) — a different id breaks deep links |
 * | `itemCount`      | counts *distinct* saves| `genre` is a list, so summing memberships reported "2 items" over a one-movie library |
 * | `[unclear]`      | never a facet value    | A folder named after our own uncertainty                              |
 */

import type { KnowledgeGroup } from '@/data/repository';
import { FACETS, displayName, isUsableFacetValue, normaliseType, slug, titleCase } from '@/knowledge/facets';

/** Separates the type segment from the facet segment in an id. */
const ID_SEPARATOR = '~';

/**
 * Minimum number of saves that must share a facet value before it gets its own
 * subgroup. Values shared by fewer saves fall to the parent's loose list
 * instead — a subgroup of 1 is just noise.
 */
const MIN_GROUP_SIZE = 5;

/**
 * The only thing the tree needs from a save — mirrors `GroupService.SaveFacts`.
 * Plain data so `buildTree` is a pure function testable without a store.
 */
export interface GroupSaveFacts {
  id: string;
  knowledgeType: string | undefined;
  structuredData: Record<string, unknown>;
}

/** Pure: no store, no network, no clock. Everything interesting is here. */
export function buildTree(ready: GroupSaveFacts[], minGroupSize: number = MIN_GROUP_SIZE): KnowledgeGroup[] {
  // Insertion-ordered so the tree is stable between calls: saves arrive
  // newest-first, so the most recently added type leads. Reshuffling the whole
  // grid on every refresh is exactly what a Map avoids and a plain object's
  // key order would not guarantee for numeric-looking type names.
  const byType = new Map<string, GroupSaveFacts[]>();
  for (const save of ready) {
    const type = normaliseType(save.knowledgeType);
    // `unusable` is the model reporting it could not extract anything. A folder
    // of failures is not a category the user asked for. Belt and braces — the
    // classifier marks those `failed`, so they never reach a ready list — but
    // the two rules live far apart and this one is cheap.
    if (type === null || type === 'unusable') continue;
    const list = byType.get(type) ?? [];
    list.push(save);
    byType.set(type, list);
  }

  const groups: KnowledgeGroup[] = [];
  byType.forEach((typeSaves, type) => groups.push(buildTypeGroup(type, typeSaves, minGroupSize)));
  return groups;
}

/** Depth-first by id — mirrors `GroupService.find`. */
export function findGroup(nodes: KnowledgeGroup[], id: string): KnowledgeGroup | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const nested = findGroup(node.subgroups, id);
    if (nested) return nested;
  }
  return null;
}

/**
 * Every save id in a subtree, this level included — mirrors
 * `GroupService.collect`. `deep: false` at the call site is just
 * `node.saveIds`, which is what a folder shows beneath its subfolders.
 */
export function collectSaveIds(node: KnowledgeGroup): string[] {
  return [...node.saveIds, ...node.subgroups.flatMap(collectSaveIds)];
}

// ------------------------------------------------------------------ internals

function buildTypeGroup(type: string, typeSaves: GroupSaveFacts[], minGroupSize: number): KnowledgeGroup {
  const facetField = FACETS[type];

  const byFacet = new Map<string, GroupSaveFacts[]>();
  // A Set, insertion-ordered and deduplicating: a movie with
  // genre ["thriller","drama"] would otherwise be added to loose twice when
  // neither genre reaches MIN_GROUP_SIZE.
  const loose = new Set<string>();

  for (const save of typeSaves) {
    const values = facetField ? facetValues(save, facetField) : [];
    if (values.length === 0) {
      // No facet the model could give us. It belongs to the type, not to an
      // invented "Uncategorised" subgroup — that would be a folder describing
      // our extraction rather than their content.
      loose.add(save.id);
      continue;
    }
    // A save can carry several genres or tags, so it can legitimately appear
    // under more than one subgroup. That is a property of the data, not a bug —
    // and it is why `nodeOf` counts distinct saves rather than summing.
    for (const value of values) {
      const list = byFacet.get(value) ?? [];
      list.push(save);
      byFacet.set(value, list);
    }
  }

  const subgroups: KnowledgeGroup[] = [];
  byFacet.forEach((facetSaves, value) => {
    if (facetSaves.length >= minGroupSize) {
      subgroups.push(
        nodeOf(
          `${type}${ID_SEPARATOR}${slug(value)}`,
          titleCase(value),
          [],
          facetSaves.map((s) => s.id),
        ),
      );
    } else {
      // Too few saves share this value — add them to loose so they still appear
      // under the parent type, not in a solo subgroup.
      facetSaves.forEach((s) => loose.add(s.id));
    }
  });

  return nodeOf(type, displayName(type), subgroups, [...loose]);
}

/** Pulls a facet as a list, tolerating both a string and an array of them. */
function facetValues(save: GroupSaveFacts, field: string): string[] {
  const raw = save.structuredData[field];
  if (typeof raw === 'string') {
    return isUsableFacetValue(raw) ? [raw.trim()] : [];
  }
  if (Array.isArray(raw)) {
    const out: string[] = [];
    for (const item of raw) {
      if (isUsableFacetValue(item)) out.push(item.trim());
    }
    return out;
  }
  return [];
}

/**
 * Counts *distinct* saves in the subtree — mirrors `GroupNode.of`.
 *
 * Not a sum of the children's counts, which is the obvious version and is
 * wrong: a facet like `genre` is a list, so one film filed under Thriller and
 * Drama is two memberships and one save. Summing reported "Watchlist · 2 items"
 * over a library holding a single movie.
 */
function nodeOf(id: string, name: string, subgroups: KnowledgeGroup[], saveIds: string[]): KnowledgeGroup {
  const distinct = new Set(saveIds);
  const collectInto = (node: KnowledgeGroup) => {
    node.saveIds.forEach((s) => distinct.add(s));
    node.subgroups.forEach(collectInto);
  };
  subgroups.forEach(collectInto);
  return { id, name, itemCount: distinct.size, subgroups, saveIds };
}
