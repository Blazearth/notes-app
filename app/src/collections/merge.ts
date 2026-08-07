/**
 * Pure port of `CollectionService`'s merge core (`api/.../collection/CollectionService.java`).
 *
 * The server is the merge authority for the real app — this exists so
 * `mockRepository` can derive real `GET /v1/collections`-shaped data from
 * `MOCK_SAVES` (the same way the server derives it from `saves`) instead of
 * a hand-authored, un-mergeable fixture tree, and so the merge logic itself
 * can be executed standalone under node against fixtures — this repo's
 * standing verification technique (`docs/testing.md`) — without booting the
 * JVM. Kept in step with the Java version by hand; `CollectionServiceTest
 * .java` is the source of truth for merge behaviour if the two drift.
 */

import { entityKey as computeEntityKey } from './entities';

export interface CollectionSource {
  saveId: string;
  savedAt: string;
  item: Record<string, unknown>;
}

export interface CollectionEntity {
  entityKey: string;
  name: string;
  kind: string;
  fields: Record<string, unknown>;
  sources: CollectionSource[];
  sourceCount: number;
  state?: Record<string, unknown>;
}

export interface CollectionNode {
  id: string;
  name: string;
  description?: string;
  entityCount: number;
  doneCount: number;
  sourceCount: number;
  subgroups: CollectionNode[];
  entityKeys: string[];
  saveIds: string[];
}

export interface CollectionSaveFacts {
  id: string;
  knowledgeType: string | undefined;
  structuredData: Record<string, unknown>;
  createdAt: string;
}

interface ItemShape {
  itemsField: string;
  nameField: string;
  kindField: string | null;
  fixedKind: string | null;
}

/** Mirrors `CollectionService.ITEM_SHAPES` — shape 1 (item-bearing list types) only, exactly K1's scope. */
const ITEM_SHAPES: Record<string, ItemShape> = {
  recommendation_list: { itemsField: 'items', nameField: 'name', kindField: 'kind', fixedKind: null },
  itinerary: { itemsField: 'places', nameField: 'name', kindField: 'kind', fixedKind: null },
  checklist: { itemsField: 'items', nameField: 'text', kindField: null, fixedKind: 'task' },
};

interface Shape2Def {
  nameField: string;
  fixedKind: string;
}

/**
 * K4, shape 2: mirrors `CollectionService.SHAPE2_TYPES` — save-is-the-entity
 * types that join an *existing* shape-1 entity as an additional source, and
 * never produce a top-level node of their own (see `mergeType`/`buildTree`
 * below, which still gate on `ITEM_SHAPES` only).
 */
const SHAPE2_TYPES: Record<string, Shape2Def> = {
  movie: { nameField: 'title', fixedKind: 'movie' },
  book: { nameField: 'title', fixedKind: 'book' },
  place: { nameField: 'name', fixedKind: 'place' },
  product: { nameField: 'title', fixedKind: 'product' },
  recipe: { nameField: 'title', fixedKind: 'recipe' },
  github_repo: { nameField: 'name', fixedKind: 'github_repo' },
};

/**
 * K4c: the user's curation over the derived view — mirrors
 * `CollectionOverrides.java`. `mergeRedirects` is keyed by the losing entity
 * key and already fully chased through any chain; `entityNames`/
 * `collectionNames` are keyed by the post-redirect id. Threaded as plain
 * data into the pure functions below, exactly like the Java side, so the
 * merge core stays database-free.
 */
export interface CollectionOverrides {
  mergeRedirects: Record<string, string>;
  entityNames: Record<string, string>;
  collectionNames: Record<string, string>;
}

export const EMPTY_OVERRIDES: CollectionOverrides = { mergeRedirects: {}, entityNames: {}, collectionNames: {} };

function resolveKey(key: string, overrides: CollectionOverrides): string {
  return overrides.mergeRedirects[key] ?? key;
}

/** Mirrors the entity-bearing subset of `KnowledgeFacets.FACETS`. */
const FACETS: Record<string, string> = {
  recommendation_list: 'medium',
  checklist: 'category',
  itinerary: 'destination',
};

/** Mirrors the entity-bearing subset of `KnowledgeFacets.DISPLAY_NAMES`. */
const DISPLAY_NAMES: Record<string, string> = {
  recommendation_list: 'Recommendations',
  checklist: 'Checklists',
  itinerary: 'Itineraries',
};

const MIN_GROUP_SIZE = 5;
const ID_SEPARATOR = '~';

function displayName(type: string): string {
  return DISPLAY_NAMES[type] ?? titleCase(type);
}

function titleCase(value: string): string {
  const trimmed = value.trim();
  return trimmed ? trimmed.charAt(0).toUpperCase() + trimmed.slice(1) : trimmed;
}

/** URL-safe and stable — mirrors `CollectionService.slug`. */
function slug(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
}

function isUsable(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.toLowerCase() !== '[unclear]';
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function normaliseType(type: string | null | undefined): string | null {
  if (!type || !type.trim()) return null;
  return type.trim().toLowerCase();
}

interface Occurrence {
  saveId: string;
  savedAt: string;
  rawName: string;
  item: Record<string, unknown>;
}

interface TypeIndex {
  byEntity: Map<string, Occurrence[]>;
  entityKeysByFacet: Map<string, Set<string>>;
  looseEntityKeys: Set<string>;
}

function itemsOf(save: CollectionSaveFacts, itemsField: string): Record<string, unknown>[] {
  const raw = save.structuredData[itemsField];
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (v): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v),
  );
}

function singleFacetValue(save: CollectionSaveFacts, field: string): string | null {
  const raw = save.structuredData[field];
  return typeof raw === 'string' && isUsable(raw) ? raw.trim() : null;
}

/**
 * K4, shape 2: every ready save of a save-is-the-entity type, reduced to a
 * single synthetic occurrence of itself — mirrors
 * `CollectionService.shape2Occurrences`. Computed once over the whole ready
 * list, not per shape-1 type, since a movie save's own `knowledgeType`
 * never matches the shape-1 type it might join.
 */
function shape2Occurrences(
  ready: CollectionSaveFacts[],
  overrides: CollectionOverrides,
): Map<string, Occurrence[]> {
  const byEntity = new Map<string, Occurrence[]>();
  for (const save of ready) {
    const type = normaliseType(save.knowledgeType);
    const def = type ? SHAPE2_TYPES[type] : undefined;
    if (!def) continue;

    const rawNameValue = save.structuredData[def.nameField];
    if (!isUsable(rawNameValue)) continue;
    const rawName = rawNameValue.trim();

    const canonicalId = asString(save.structuredData.tmdbId);
    const key = resolveKey(computeEntityKey(def.fixedKind, rawName, canonicalId), overrides);

    const item = { ...save.structuredData };
    delete item[def.nameField];

    const occurrences = byEntity.get(key) ?? [];
    occurrences.push({ saveId: save.id, savedAt: save.createdAt, rawName, item });
    byEntity.set(key, occurrences);
  }
  return byEntity;
}

/**
 * Walks every save's items once, bucketing by entity key and by facet value
 * — mirrors `CollectionService.index`. K4 folds in the same two things at
 * the same point the Java side does: a manual-merge redirect
 * (`resolveKey`), and any shape-2 save that independently computed the same
 * (post-redirect) key — never the other way around, a shape-2 save never
 * creates an entity of its own here.
 */
function indexType(
  type: string,
  typeSaves: CollectionSaveFacts[],
  shape2ByKey: Map<string, Occurrence[]>,
  overrides: CollectionOverrides,
): TypeIndex {
  const shape = ITEM_SHAPES[type];
  const facetField = FACETS[type];
  const byEntity = new Map<string, Occurrence[]>();
  const entityKeysByFacet = new Map<string, Set<string>>();
  const loose = new Set<string>();

  for (const save of typeSaves) {
    const items = itemsOf(save, shape.itemsField);
    const facetValue = facetField ? singleFacetValue(save, facetField) : null;

    for (const item of items) {
      const rawNameValue = item[shape.nameField];
      if (!isUsable(rawNameValue)) continue;
      const rawName = rawNameValue.trim();
      const kindForKey = shape.kindField ? asString(item[shape.kindField]) : shape.fixedKind;
      const canonicalId = asString(item.tmdbId);
      const key = resolveKey(computeEntityKey(kindForKey, rawName, canonicalId), overrides);

      const occurrences = byEntity.get(key) ?? [];
      occurrences.push({ saveId: save.id, savedAt: save.createdAt, rawName, item });
      byEntity.set(key, occurrences);

      if (facetValue !== null) {
        const set = entityKeysByFacet.get(facetValue) ?? new Set<string>();
        set.add(key);
        entityKeysByFacet.set(facetValue, set);
      } else {
        loose.add(key);
      }
    }
  }

  shape2ByKey.forEach((occurrences, key) => {
    const existing = byEntity.get(key);
    if (existing) existing.push(...occurrences);
  });

  return { byEntity, entityKeysByFacet, looseEntityKeys: loose };
}

/** The most common surface form across sources; ties go to the earliest save. */
function resolveName(occurrences: Occurrence[]): string {
  const counts = new Map<string, number>();
  const earliest = new Map<string, string>();
  for (const o of occurrences) {
    counts.set(o.rawName, (counts.get(o.rawName) ?? 0) + 1);
    const current = earliest.get(o.rawName);
    if (current === undefined || o.savedAt < current) earliest.set(o.rawName, o.savedAt);
  }
  let best: string | null = null;
  for (const candidate of counts.keys()) {
    const candidateCount = counts.get(candidate) as number;
    const bestCount = best === null ? -1 : (counts.get(best) as number);
    if (
      best === null ||
      candidateCount > bestCount ||
      (candidateCount === bestCount && (earliest.get(candidate) as string) < (earliest.get(best) as string))
    ) {
      best = candidate;
    }
  }
  return best as string;
}

function resolveKind(occurrences: Occurrence[], kindField: string | null, fixedKind: string | null): string {
  if (!kindField) return fixedKind as string;
  for (const o of occurrences) {
    const value = asString(o.item[kindField]);
    if (isUsable(value)) return (value as string).trim();
  }
  return '[unclear]';
}

/** Every item field but name and kind, rolled up: a list field unions, a scalar field takes the first non-unclear value. */
function rollupFields(
  occurrences: Occurrence[],
  nameField: string,
  kindField: string | null,
): Record<string, unknown> {
  const keys = new Set<string>();
  for (const o of occurrences) {
    for (const key of Object.keys(o.item)) {
      if (key !== nameField && key !== kindField) keys.add(key);
    }
  }
  const rolled: Record<string, unknown> = {};
  for (const key of keys) {
    const isListField = occurrences.some((o) => Array.isArray(o.item[key]));
    rolled[key] = isListField ? rollupList(occurrences, key) : rollupScalar(occurrences, key);
  }
  return rolled;
}

function rollupList(occurrences: Occurrence[], key: string): unknown[] {
  const union: unknown[] = [];
  const seen = new Set<unknown>();
  for (const o of occurrences) {
    const value = o.item[key];
    if (!Array.isArray(value)) continue;
    for (const element of value) {
      const usable = typeof element === 'string' ? isUsable(element) : element != null;
      if (usable && !seen.has(element)) {
        seen.add(element);
        union.push(element);
      }
    }
  }
  return union;
}

function rollupScalar(occurrences: Occurrence[], key: string): unknown {
  for (const o of occurrences) {
    const value = asString(o.item[key]);
    if (isUsable(value)) return value.trim();
  }
  return '[unclear]';
}

/** One {@link CollectionSource} per distinct save — a save mentioning the same entity twice still counts once. */
function resolveSources(occurrences: Occurrence[]): CollectionSource[] {
  const bySave = new Map<string, CollectionSource>();
  for (const o of occurrences) {
    if (!bySave.has(o.saveId)) {
      bySave.set(o.saveId, { saveId: o.saveId, savedAt: o.savedAt, item: o.item });
    }
  }
  return [...bySave.values()];
}

function mergeEntity(
  key: string,
  occurrences: Occurrence[],
  shape: ItemShape,
  overrides: CollectionOverrides,
): CollectionEntity {
  const name = overrides.entityNames[key] ?? resolveName(occurrences);
  const kind = resolveKind(occurrences, shape.kindField, shape.fixedKind);
  const fields = rollupFields(occurrences, shape.nameField, shape.kindField);
  const sources = resolveSources(occurrences);
  return { entityKey: key, name, kind, fields, sources, sourceCount: sources.length };
}

function mergeAll(
  byEntity: Map<string, Occurrence[]>,
  shape: ItemShape,
  overrides: CollectionOverrides,
): Map<string, CollectionEntity> {
  const merged = new Map<string, CollectionEntity>();
  byEntity.forEach((occurrences, key) => merged.set(key, mergeEntity(key, occurrences, shape, overrides)));
  return merged;
}

/**
 * Pure: the merged entity list for one type, optionally filtered to a facet
 * value and with K4's overrides applied — mirrors `CollectionService.mergeType`.
 * `overrides` defaults to no-op so every existing caller (mock data with no
 * curation yet) is unaffected.
 */
export function mergeType(
  ready: CollectionSaveFacts[],
  type: string,
  facet?: string | null,
  overrides: CollectionOverrides = EMPTY_OVERRIDES,
): CollectionEntity[] {
  const normalizedType = normaliseType(type);
  if (!normalizedType || !ITEM_SHAPES[normalizedType]) return [];

  const typeSaves = ready.filter((s) => normaliseType(s.knowledgeType) === normalizedType);
  const shape2ByKey = shape2Occurrences(ready, overrides);
  const idx = indexType(normalizedType, typeSaves, shape2ByKey, overrides);
  const merged = mergeAll(idx.byEntity, ITEM_SHAPES[normalizedType], overrides);

  if (!facet || !facet.trim()) return [...merged.values()];

  const out: CollectionEntity[] = [];
  const seen = new Set<string>();
  idx.entityKeysByFacet.forEach((keys, facetValue) => {
    if (slug(facetValue) !== slug(facet)) return;
    keys.forEach((key) => {
      if (seen.has(key)) return;
      seen.add(key);
      const entity = merged.get(key);
      if (entity) out.push(entity);
    });
  });
  return out;
}

function saveIdsFor(entityKeys: Iterable<string>, merged: Map<string, CollectionEntity>): string[] {
  const ids = new Set<string>();
  for (const key of entityKeys) {
    const entity = merged.get(key);
    entity?.sources.forEach((s) => ids.add(s.saveId));
  }
  return [...ids];
}

/** Counts *distinct* entities and saves in the subtree — mirrors `CollectionNode.of`. */
function nodeOf(
  id: string,
  name: string,
  description: string | undefined,
  subgroups: CollectionNode[],
  entityKeys: string[],
  saveIds: string[],
): CollectionNode {
  const distinctEntities = new Set(entityKeys);
  const distinctSaves = new Set(saveIds);
  const collect = (node: CollectionNode) => {
    node.entityKeys.forEach((k) => distinctEntities.add(k));
    node.saveIds.forEach((s) => distinctSaves.add(s));
    node.subgroups.forEach(collect);
  };
  subgroups.forEach(collect);
  return {
    id,
    name,
    description,
    entityCount: distinctEntities.size,
    doneCount: 0,
    sourceCount: distinctSaves.size,
    subgroups,
    entityKeys,
    saveIds,
  };
}

function buildTypeNode(
  type: string,
  typeSaves: CollectionSaveFacts[],
  minGroupSize: number,
  shape2ByKey: Map<string, Occurrence[]>,
  overrides: CollectionOverrides,
): CollectionNode {
  const idx = indexType(type, typeSaves, shape2ByKey, overrides);
  const merged = mergeAll(idx.byEntity, ITEM_SHAPES[type], overrides);

  const subgroups: CollectionNode[] = [];
  const loose = new Set(idx.looseEntityKeys);

  idx.entityKeysByFacet.forEach((keys, facetValue) => {
    if (keys.size >= minGroupSize) {
      const id = `${type}${ID_SEPARATOR}${slug(facetValue)}`;
      subgroups.push(
        nodeOf(
          id,
          overrides.collectionNames[id] ?? titleCase(facetValue),
          undefined,
          [],
          [...keys],
          saveIdsFor(keys, merged),
        ),
      );
    } else {
      // Too few entities share this facet value — fold into loose so they
      // still surface under the parent type rather than a solo subgroup.
      keys.forEach((k) => loose.add(k));
    }
  });

  return nodeOf(
    type,
    overrides.collectionNames[type] ?? displayName(type),
    undefined,
    subgroups,
    [...loose],
    saveIdsFor(loose, merged),
  );
}

/**
 * Pure: the collection tree — mirrors `CollectionService.buildTree`.
 * `minGroupSize` defaults to the production threshold, `overrides` to no-op.
 */
export function buildTree(
  ready: CollectionSaveFacts[],
  minGroupSize: number = MIN_GROUP_SIZE,
  overrides: CollectionOverrides = EMPTY_OVERRIDES,
): CollectionNode[] {
  const byType = new Map<string, CollectionSaveFacts[]>();
  for (const save of ready) {
    const type = normaliseType(save.knowledgeType);
    if (!type || !ITEM_SHAPES[type]) continue;
    const list = byType.get(type) ?? [];
    list.push(save);
    byType.set(type, list);
  }
  const shape2ByKey = shape2Occurrences(ready, overrides);
  const nodes: CollectionNode[] = [];
  byType.forEach((typeSaves, type) => nodes.push(buildTypeNode(type, typeSaves, minGroupSize, shape2ByKey, overrides)));
  return nodes;
}

/** Recomputes `doneCount` across a subtree from a set of known-done entity keys — mirrors `CollectionNode.withDoneCount`. */
export function withDoneCount(node: CollectionNode, doneEntityKeys: ReadonlySet<string>): CollectionNode {
  const subgroups = node.subgroups.map((child) => withDoneCount(child, doneEntityKeys));
  const all = new Set<string>();
  const collect = (n: CollectionNode) => {
    n.entityKeys.forEach((k) => all.add(k));
    n.subgroups.forEach(collect);
  };
  collect(node);
  let done = 0;
  all.forEach((k) => {
    if (doneEntityKeys.has(k)) done += 1;
  });
  return { ...node, doneCount: done, subgroups };
}
