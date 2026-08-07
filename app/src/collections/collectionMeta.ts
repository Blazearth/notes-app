/**
 * Product-facing vocabulary for a collection's entity/done counts —
 * "3 anime · 1 watched" reads better than "3 items · 1 done" for a
 * watchlist, and "2 tasks · 1 done" is honest for a checklist. Purely
 * cosmetic: the underlying `CollectionNodeResponse`/`CollectionEntityResponse`
 * shapes are the same for every entity-bearing type.
 */

export interface CollectionTypeMeta {
  entityNoun: (count: number) => string;
  doneNoun: string;
  /** Whether this type's entity detail offers a 1-5 star rating alongside "done" — recommendation_list only, for now. */
  ratable: boolean;
}

const META: Record<string, CollectionTypeMeta> = {
  recommendation_list: {
    entityNoun: (n) => (n === 1 ? 'title' : 'titles'),
    doneNoun: 'watched',
    ratable: true,
  },
  checklist: {
    entityNoun: (n) => (n === 1 ? 'task' : 'tasks'),
    doneNoun: 'done',
    ratable: false,
  },
  itinerary: {
    entityNoun: (n) => (n === 1 ? 'place' : 'places'),
    doneNoun: 'visited',
    ratable: false,
  },
};

const FALLBACK: CollectionTypeMeta = {
  entityNoun: (n) => (n === 1 ? 'item' : 'items'),
  doneNoun: 'done',
  ratable: false,
};

export function collectionTypeMeta(type: string): CollectionTypeMeta {
  return META[type] ?? FALLBACK;
}
