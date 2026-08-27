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

import { axesFor, axisDisplayName, canonicalKind, derivedValues, type CollectionAxis } from './axes';
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

/**
 * Mirrors `CollectionService.ITEM_SHAPES` — the item-bearing types.
 *
 * `workout` joins here rather than staying shape 3: its `exercises[]` *is* an
 * item-bearing list, and merging it gives "Bench Press, in 3 of your push
 * days" — the same cross-source aggregation every other entry produces. What
 * stays out of scope is synthesising a *new* routine from several saves; a
 * merged exercise is a fact about the library, not an invented program.
 */
const ITEM_SHAPES: Record<string, ItemShape> = {
  recommendation_list: { itemsField: 'items', nameField: 'name', kindField: 'kind', fixedKind: null },
  itinerary: { itemsField: 'places', nameField: 'name', kindField: 'kind', fixedKind: null },
  checklist: { itemsField: 'items', nameField: 'text', kindField: null, fixedKind: 'task' },
  workout: { itemsField: 'exercises', nameField: 'name', kindField: null, fixedKind: 'exercise' },
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

/** Mirrors the entity-bearing subset of `KnowledgeFacets.DISPLAY_NAMES`. */
const DISPLAY_NAMES: Record<string, string> = {
  recommendation_list: 'Recommendations',
  checklist: 'Checklists',
  itinerary: 'Itineraries',
  workout: 'Workouts',
};

export const ID_SEPARATOR = '~';

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

/**
 * One type's items bucketed by entity key, plus — per entity — the save-level
 * field values every save that contributed it carried.
 *
 * K1 kept a single `facet value → entity keys` map, which could only express
 * one level of grouping by one save-level field. Recording the values *per
 * entity* instead lets `buildLevel` partition the same set repeatedly, once
 * per axis, and lets a `save` axis sit at any depth rather than only first.
 */
interface TypeIndex {
  byEntity: Map<string, Occurrence[]>;
  saveFieldValues: Map<string, Map<string, Set<string>>>;
}

/** One level's partition: the subgroups that met their threshold, and the keys left at this level. */
interface Level {
  subgroups: CollectionNode[];
  looseEntityKeys: Set<string>;
}

function itemsOf(save: CollectionSaveFacts, itemsField: string): Record<string, unknown>[] {
  const raw = save.structuredData[itemsField];
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (v): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v),
  );
}

/** A scalar string or an array of them, filtered to usable values — one reader for both shapes. */
function usableStrings(raw: unknown): string[] {
  if (typeof raw === 'string') return isUsable(raw) ? [raw.trim()] : [];
  if (Array.isArray(raw)) return raw.filter(isUsable).map((v) => v.trim());
  return [];
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
  // Only the save-level axes need reading off the save; entity-level axes are
  // answered later, from the merged entity itself.
  const saveAxisFields = [
    ...new Set(axesFor(type).filter((a) => a.from === 'save').map((a) => a.field)),
  ];
  const byEntity = new Map<string, Occurrence[]>();
  const saveFieldValues = new Map<string, Map<string, Set<string>>>();

  for (const save of typeSaves) {
    const items = itemsOf(save, shape.itemsField);
    const axisValuesForSave = saveAxisFields.map(
      (field) => [field, usableStrings(save.structuredData[field])] as const,
    );

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

      // The same entity reached by two saves accumulates both saves' values,
      // so a place named by a Japan itinerary and a Tokyo one files under both
      // rather than whichever landed last.
      const forEntity = saveFieldValues.get(key) ?? new Map<string, Set<string>>();
      for (const [field, values] of axisValuesForSave) {
        const set = forEntity.get(field) ?? new Set<string>();
        values.forEach((v) => set.add(v));
        forEntity.set(field, set);
      }
      saveFieldValues.set(key, forEntity);
    }
  }

  shape2ByKey.forEach((occurrences, key) => {
    const existing = byEntity.get(key);
    if (existing) existing.push(...occurrences);
  });

  return { byEntity, saveFieldValues };
}

/**
 * One entity's values on one axis, already derived and de-duplicated. A
 * `save` axis reads what the contributing saves carried (recorded per entity
 * by `indexType`); an `entity` axis reads the merged entity — its resolved
 * `kind`, or a rolled-up field, scalar or unioned list.
 */
function axisValues(
  axis: CollectionAxis,
  entityKeyValue: string,
  entity: CollectionEntity | undefined,
  idx: TypeIndex,
): string[] {
  if (!entity) return [];
  const raw =
    axis.from === 'save'
      ? [...(idx.saveFieldValues.get(entityKeyValue)?.get(axis.field) ?? [])]
      : entityFieldValues(entity, axis.field);
  return derivedValues(axis, raw);
}

function entityFieldValues(entity: CollectionEntity, field: string): string[] {
  // Canonicalised, not just trimmed — bucketing by the raw extracted spelling
  // would file "movie" and "film" as two separate nodes that both happen to
  // display as "Movies". See `canonicalKind`.
  if (field === 'kind') return isUsable(entity.kind) ? [canonicalKind(entity.kind)] : [];
  return usableStrings(entity.fields[field]);
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
 * A facet is just the depth-1 node under a type, so this is a thin
 * translation onto {@link mergeNode}.
 */
export function mergeType(
  ready: CollectionSaveFacts[],
  type: string,
  facet?: string | null,
  overrides: CollectionOverrides = EMPTY_OVERRIDES,
): CollectionEntity[] {
  const nodeId = facet && facet.trim() ? `${type}${ID_SEPARATOR}${slug(facet)}` : type;
  return mergeNode(ready, nodeId, overrides);
}

/**
 * Pure: the merged entities under one node of the tree — a bare type id for
 * all of them, or a path like `recommendation_list~anime~romance` for one
 * subtree. Mirrors `CollectionService.mergeNode`.
 *
 * Resolved by **walking the axis path** — filtering the merged set by each
 * segment's axis value in turn — rather than by building the tree and finding
 * the node in it. A tree lookup would make the entity list depend on
 * `minGroupSize`: a subgroup too small to be worth *showing* would answer
 * with nothing at all rather than with its entities, so a perfectly
 * well-formed node id would resolve empty. It also costs a whole tree build
 * to answer what is really just a filter.
 */
export function mergeNode(
  ready: CollectionSaveFacts[],
  nodeId: string,
  overrides: CollectionOverrides = EMPTY_OVERRIDES,
): CollectionEntity[] {
  const path = nodeId ? nodeId.split(ID_SEPARATOR) : [];
  const normalizedType = path.length > 0 ? normaliseType(path[0]) : null;
  if (!normalizedType || !ITEM_SHAPES[normalizedType]) return [];

  const typeSaves = ready.filter((s) => normaliseType(s.knowledgeType) === normalizedType);
  const shape2ByKey = shape2Occurrences(ready, overrides);
  const idx = indexType(normalizedType, typeSaves, shape2ByKey, overrides);
  const merged = mergeAll(idx.byEntity, ITEM_SHAPES[normalizedType], overrides);

  const axes = axesFor(normalizedType);
  let wanted = new Set(merged.keys());
  for (let depth = 1; depth < path.length; depth += 1) {
    // The path is deeper than the type's axis chain — not a node this type
    // can ever produce.
    if (depth > axes.length) return [];
    const axis = axes[depth - 1];
    const segment = path[depth];
    wanted = new Set(
      [...wanted].filter((key) =>
        axisValues(axis, key, merged.get(key), idx).some((value) => slug(value) === segment),
      ),
    );
  }

  return [...merged.entries()].filter(([key]) => wanted.has(key)).map(([, entity]) => entity);
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
  minGroupSizeOverride: number | null,
  shape2ByKey: Map<string, Occurrence[]>,
  overrides: CollectionOverrides,
): CollectionNode {
  const idx = indexType(type, typeSaves, shape2ByKey, overrides);
  const merged = mergeAll(idx.byEntity, ITEM_SHAPES[type], overrides);

  const root = buildLevel(type, axesFor(type), 0, [...merged.keys()], merged, idx, minGroupSizeOverride, overrides);

  return nodeOf(
    type,
    overrides.collectionNames[type] ?? displayName(type),
    undefined,
    root.subgroups,
    [...root.looseEntityKeys],
    saveIdsFor(root.looseEntityKeys, merged),
  );
}

/**
 * Partitions one set of entity keys by the axis at `depth`, then recurses
 * into each surviving bucket with the next axis — the whole of the
 * multi-level grouping, in one function that knows nothing about any
 * particular type or field. Mirrors `CollectionService.buildLevel`.
 *
 * Two rules are load-bearing:
 *
 * - **An entity may sit in several buckets at one level** (a two-genre
 *   title), so buckets are built by adding, never by moving. `nodeOf` then
 *   counts distinct entities across the subtree rather than summing children,
 *   which is what keeps "14 titles" from reading as 19.
 * - **A key falls through to loose only if *no* bucket it landed in
 *   survived.** Deciding per bucket instead would list a title inside Romance
 *   *and* loose beside it, because its second genre happened to be rare.
 */
function buildLevel(
  idPrefix: string,
  axes: CollectionAxis[],
  depth: number,
  entityKeys: string[],
  merged: Map<string, CollectionEntity>,
  idx: TypeIndex,
  minGroupSizeOverride: number | null,
  overrides: CollectionOverrides,
): Level {
  if (depth >= axes.length || entityKeys.length === 0) {
    return { subgroups: [], looseEntityKeys: new Set(entityKeys) };
  }

  const axis = axes[depth];
  const threshold = minGroupSizeOverride ?? axis.minGroupSize;

  const buckets = new Map<string, Set<string>>();
  const unbucketed = new Set<string>();
  for (const key of entityKeys) {
    const values = axisValues(axis, key, merged.get(key), idx);
    if (values.length === 0) {
      // No usable value on this axis — [unclear], absent, or an empty array.
      // The entity stays at this level rather than being filed under a guess,
      // the same sentinel contract as everywhere else.
      unbucketed.add(key);
      continue;
    }
    for (const value of values) {
      const set = buckets.get(value) ?? new Set<string>();
      set.add(key);
      buckets.set(value, set);
    }
  }

  const subgroups: CollectionNode[] = [];
  const claimed = new Set<string>();
  buckets.forEach((keys, value) => {
    if (keys.size < threshold) return;
    const childId = `${idPrefix}${ID_SEPARATOR}${slug(value)}`;
    const child = buildLevel(childId, axes, depth + 1, [...keys], merged, idx, minGroupSizeOverride, overrides);
    subgroups.push(
      nodeOf(
        childId,
        overrides.collectionNames[childId] ?? axisDisplayName(axis, value),
        undefined,
        child.subgroups,
        [...child.looseEntityKeys],
        saveIdsFor(child.looseEntityKeys, merged),
      ),
    );
    keys.forEach((k) => claimed.add(k));
  });

  const loose = new Set(unbucketed);
  for (const key of entityKeys) {
    if (!claimed.has(key)) loose.add(key);
  }
  return { subgroups, looseEntityKeys: loose };
}

/**
 * Pure: the collection tree — mirrors `CollectionService.buildTree`.
 * `minGroupSizeOverride` is null in production, where each axis carries its
 * own threshold; a number replaces every axis's threshold, which is what
 * lets a small fixture still produce the full tree.
 */
export function buildTree(
  ready: CollectionSaveFacts[],
  minGroupSizeOverride: number | null = null,
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
  byType.forEach((typeSaves, type) => {
    const node = buildTypeNode(type, typeSaves, minGroupSizeOverride, shape2ByKey, overrides);
    // A type whose saves carried no usable items — a workout save with no
    // `exercises`, a list whose every item name was `[unclear]` — produces no
    // entities, and an empty collection is worse than no collection: the
    // Library hides a type's individual saves once it has a collection to
    // show instead, so an empty node would hide them behind nothing.
    if (node.entityCount > 0) nodes.push(node);
  });
  return nodes;
}

/** Depth-first lookup of one node in a built tree — what a route param resolves through. */
export function findCollectionNode(nodes: CollectionNode[], id: string): CollectionNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const found = findCollectionNode(node.subgroups, id);
    if (found) return found;
  }
  return null;
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
