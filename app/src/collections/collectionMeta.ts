/**
 * Product-facing vocabulary for a collection — what its entities are called,
 * what "done" means for them, and what its folders are called.
 *
 * Purely cosmetic: the underlying `CollectionNodeResponse`/
 * `CollectionEntityResponse` shapes are identical for every entity-bearing
 * type. But the naming is what stops a knowledge collection reading like a
 * task list — an itinerary's entities are *places*, not "items remaining",
 * and a section header saying so is the difference between a folder and a
 * to-do list.
 */

import { ID_SEPARATOR } from './merge';

export interface CollectionTypeMeta {
  /** "7 places", "14 titles" — this collection's entities. */
  entityNoun: (count: number) => string;
  /** What marking an entity done means here: watched, visited, done. */
  doneNoun: string;
  /** Whether this type's entity detail offers a 1-5 star rating alongside "done". */
  ratable: boolean;
  /**
   * The heading over the entity list. Replaces the generic "Remaining",
   * which read every collection as a checklist — right for `checklist`, wrong
   * for a country's places or a split's exercises.
   */
  sectionLabel: string;
  /** "2 destinations", "3 genres" — this collection's folders at a given depth. */
  groupNoun: (count: number, depth: number) => string;
  /**
   * Whether an entity's `kind` says anything the section heading has not
   * already said.
   *
   * `recommendation_list` and `itinerary` carry a *per-item* kind — anime vs
   * film, sight vs restaurant — which is real information. `checklist` and
   * `workout` have a fixed kind every item shares, so rendering it puts the
   * word "exercise" under every row of a screen headed EXERCISES. That is the
   * extraction structure leaking into the interface for no benefit.
   */
  showsKind: boolean;
}

const plural = (singular: string, pluralForm?: string) => (n: number) =>
  n === 1 ? singular : (pluralForm ?? `${singular}s`);

const META: Record<string, CollectionTypeMeta> = {
  recommendation_list: {
    entityNoun: plural('title'),
    doneNoun: 'watched',
    ratable: true,
    sectionLabel: 'Watchlist',
    groupNoun: (n, depth) => (depth === 0 ? plural('category', 'categories')(n) : plural('genre')(n)),
    showsKind: true,
  },
  checklist: {
    entityNoun: plural('task'),
    doneNoun: 'done',
    ratable: false,
    sectionLabel: 'Tasks',
    groupNoun: plural('category', 'categories'),
    showsKind: false,
  },
  itinerary: {
    entityNoun: plural('place'),
    doneNoun: 'visited',
    ratable: false,
    sectionLabel: 'Places',
    groupNoun: plural('destination'),
    showsKind: true,
  },
  workout: {
    entityNoun: plural('exercise'),
    doneNoun: 'done',
    ratable: false,
    sectionLabel: 'Exercises',
    groupNoun: plural('split'),
    showsKind: false,
  },
};

const FALLBACK: CollectionTypeMeta = {
  entityNoun: plural('item'),
  doneNoun: 'done',
  ratable: false,
  sectionLabel: 'Items',
  groupNoun: plural('group'),
  showsKind: true,
};

export function collectionTypeMeta(type: string): CollectionTypeMeta {
  return META[type] ?? FALLBACK;
}

/**
 * Nouns for the per-item `kind` vocabulary, so "Recommendations →
 * Restaurants" counts *places* rather than inheriting the parent's "titles".
 * Only kinds whose natural noun differs from the type's own need an entry.
 */
const KIND_NOUNS: Record<string, (count: number) => string> = {
  restaurant: plural('place'),
  place: plural('place'),
  sight: plural('place'),
  hotel: plural('place'),
  area: plural('place'),
  product: plural('product'),
  book: plural('book'),
  game: plural('game'),
  podcast: plural('episode'),
};

/**
 * The right noun for one node's entities.
 *
 * A node id is `type~value~value` (see `CollectionService.slug`), and for a
 * type whose first axis is the item's own `kind`, that first segment *is* the
 * kind — so "3 places" under Restaurants falls out of the id without the node
 * payload carrying a noun. Every other node inherits its type's noun.
 */
export function nodeEntityNoun(type: string, nodeId: string, count: number): string {
  const segments = nodeId.split(ID_SEPARATOR);
  if (type === 'recommendation_list' && segments.length > 1) {
    const kindNoun = KIND_NOUNS[segments[1]];
    if (kindNoun) return kindNoun(count);
  }
  return collectionTypeMeta(type).entityNoun(count);
}

/** The knowledge type a node id belongs to — its first segment, always. */
export function nodeType(nodeId: string): string {
  return nodeId.split(ID_SEPARATOR)[0];
}

/** How deep a node sits: 0 for a type root, 1 for its folders, and so on. */
export function nodeDepth(nodeId: string): number {
  return nodeId.split(ID_SEPARATOR).length - 1;
}
