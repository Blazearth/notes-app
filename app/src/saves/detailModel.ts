import type { SaveResponse } from '@/api/types';
import { entityKey as computeEntityKey } from '@/collections/entities';
import { estimateSessionLoad } from '@/collections/workoutLoad';
import { groupByDay } from '@/collections/placeGroups';

/** One row inside a {@link DetailObject} — a labelled value or chip list. */
export interface DetailObjectRow {
  label: string;
  text?: string;
  items?: string[];
}

/**
 * One entry of a nested object array — an exercise, a structured ingredient.
 * Renders as a sub-card: title, a compact meta line, then labelled rows.
 */
export interface DetailObject {
  title?: string;
  meta?: string;
  rows: DetailObjectRow[];
  /**
   * Position inside `structuredData` this object represents — `"exercises[2]"`,
   * `"items[0]"`. Present only when the array index is stable (the whole
   * mechanism's precondition — see `SaveItemStateService`'s doc comment) and
   * the type has a Phase 4 behavior defined for it. Absent for generic
   * `objectListField` leftovers, which have no bespoke control to attach.
   */
  statePath?: string;
  /** Which Phase 4 control this card shows, if any — see `docs/next-phases.md` §4.2. */
  control?: 'check' | 'watch';
  /**
   * K2: for a `recommendation_list` item, `Entities.key(kind, name)` —
   * present alongside `statePath` so the control can dual-read (`entity
   * state ?? item state`, entity wins) and, once touched, write to
   * `PATCH /v1/entity-state` instead of `PATCH /v1/saves/{id}/item-state`.
   * Watched-ness is a property of the entity, not of this one save's
   * mention of it. Absent for every other type — checklist/course/workout
   * completion stays save-item-keyed per `docs/knowledge-collections.md`
   * ("Stored state").
   */
  entityKey?: string;
  /** A workout exercise's raw `rest` text ("90s", "2 min") — renders a rest-timer button. */
  restLabel?: string;
  /** Pure URL construction from name + area/address — an "Open in Maps" button, no state. */
  mapsUrl?: string;
  /**
   * A poster/cover image — currently only `recommendation_list` items that
   * `RecommendationListEnricher` (§5.3) found a TMDB match for. Additive and
   * absent on most items: enrichment is best-effort and only covers the
   * first several screen-kind items on a save.
   */
  imageUrl?: string;
}

/**
 * A sub-heading inside an `objects`-style field — an itinerary's places
 * clustered by area and day, so "45 identical cards" becomes "Tokyo, day
 * 1–3" / "Mt. Fuji, day 4" / "Kyoto, day 5–7" instead of one flat list.
 * `objects` here is a subset of the parent field's own `objects`, in the
 * order they should render within the group — nothing here is generated or
 * merged across sources, just the extracted `area`/`day` used to cluster.
 */
export interface DetailFieldGroup {
  label: string;
  objects: DetailObject[];
}

/** A field rendered as its own block: one value, a list of them, or objects. */
export interface DetailField {
  label: string;
  /** Exactly one of these is set. */
  text?: string;
  items?: string[];
  objects?: DetailObject[];
  /**
   * When present, `objects` above should be rendered clustered under these
   * headings instead of as one flat list. Absent when clustering would say
   * nothing — a single area, or no place stating one at all.
   */
  groups?: DetailFieldGroup[];
  /** Steps render numbered; ingredients and tags render as chips. */
  style: 'text' | 'chips' | 'steps' | 'objects';
  /**
   * done/total across this field's `control: 'check'` objects — a progress
   * bar for checklist items and course sections. Derived from the caller's
   * item states at render time, never stored itself.
   */
  progress?: { done: number; total: number };
  /**
   * True for a field that reached the screen only because `leftovers()`
   * didn't recognise it — a genuine extracted or enriched value, just not one
   * curated for this type. The screen renders these behind a "More details"
   * disclosure rather than at the same priority as the fields a type's own
   * bespoke branch chose deliberately. Never set on a bespoke field.
   */
  secondary?: boolean;
}

export interface SaveDetailModel {
  title: string;
  /** The single line under the title — type, year, cuisine, that sort of thing. */
  meta?: string;
  /** The one paragraph worth reading first, when the type has one. */
  lede?: string;
  fields: DetailField[];
  /** A single-place save's "Open in Maps" target — `place` only; `itinerary`'s are per-item. */
  mapsUrl?: string;
}

const UNCLEAR = '[unclear]';

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== UNCLEAR ? trimmed : null;
}

function cleanList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => clean(v))
    .filter((v): v is string => v !== null);
}

function join(parts: Array<string | null>): string | undefined {
  const kept = parts.filter((p): p is string => !!p);
  return kept.length ? kept.join(' · ') : undefined;
}

/** `dietaryNotes` → `Dietary notes`. Mirrors `EmbeddingProfile.label` server-side. */
function humanise(key: string): string {
  const spaced = key.replace(/(?<=[a-z0-9])(?=[A-Z])/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

function textField(label: string, value: unknown): DetailField | null {
  const text = clean(value);
  return text ? { label, text, style: 'text' } : null;
}

function listField(
  label: string,
  value: unknown,
  style: 'chips' | 'steps' = 'chips',
): DetailField | null {
  const items = cleanList(value);
  return items.length ? { label, items, style } : null;
}

function compact(fields: Array<DetailField | null>): DetailField[] {
  return fields.filter((f): f is DetailField => f !== null);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

type ItemStates = Record<string, Record<string, unknown>> | undefined;
/** K2 entity state, keyed by `Entities.key`'s output — see `DetailObject.entityKey`. */
export type EntityStates = Record<string, Record<string, unknown>> | undefined;

/** Google Maps' web deep link — works from any platform without a native module. */
function mapsUrl(query: string): string {
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
}

/**
 * done/total across a set of `control: 'check'` or `control: 'watch'`
 * objects — the checklist progress bar, course "continue where I left off",
 * and (§5.2) the watchlist's watched-count, all derived here rather than
 * stored (`docs/next-phases.md` §4.2: "count of done/total — derived in the
 * client, never stored"; §5.2 extends the same derivation to `recommendation_list`).
 * Both controls key their "done" state the same way (`state.done === true`),
 * so one function covers a tick and a watched-mark alike.
 */
function checkProgress(
  objects: DetailObject[],
  itemStates: ItemStates,
  entityStates?: EntityStates,
): { done: number; total: number } | undefined {
  const trackable = objects.filter(
    (o) => (o.control === 'check' || o.control === 'watch') && (o.statePath || o.entityKey),
  );
  if (!trackable.length) return undefined;
  const done = trackable.filter((o) => resolveObjectState(o, itemStates, entityStates)?.done === true).length;
  return { done, total: trackable.length };
}

/**
 * K2 dual-read: entity state wins over item state when both exist for the
 * same object — `docs/knowledge-collections.md` ("Stored state")'s
 * migration rule. An object with no `entityKey` (every type but
 * `recommendation_list`) falls straight through to item state, unchanged
 * from Phase 4.
 */
function resolveObjectState(
  object: DetailObject,
  itemStates: ItemStates,
  entityStates: EntityStates,
): Record<string, unknown> | undefined {
  if (object.entityKey) {
    const fromEntity = entityStates?.[object.entityKey];
    if (fromEntity) return fromEntity;
  }
  return object.statePath ? itemStates?.[object.statePath] : undefined;
}

/**
 * §5.2 local compute: total sets and an estimated session length from the
 * routine's own `sets`/`rest` values — arithmetic on what the model already
 * extracted, never a model call and never invented content. Only estimates a
 * duration when the content did not already state one (`workout.duration`) —
 * no point guessing at what the creator already said.
 *
 * The estimate itself is `@/collections/workoutLoad`'s `estimateSessionLoad`
 * — shared with the collection layer's per-split summary card, which runs the
 * identical arithmetic over a *merged* split's exercises. Keeping one copy is
 * what stops the two figures reading differently for the same exercises.
 */
function workoutLoadField(exercises: unknown, statedDuration: string | null): DetailField | null {
  if (!Array.isArray(exercises)) return null;
  const usable = exercises.filter(isRecord);
  const load = estimateSessionLoad(usable);
  if (!load) return null;

  const parts = [
    `${load.totalSets} set${load.totalSets === 1 ? '' : 's'} across ${load.countedExercises} exercise${load.countedExercises === 1 ? '' : 's'}`,
  ];
  if (!statedDuration) parts.push(`~${load.estimatedMinutes} min (est.)`);
  return { label: 'Session load', text: parts.join(' · '), style: 'text' };
}

/**
 * Ingredients exist in two shapes forever: saves classified before 2026-08-07
 * hold flat strings ("250g mascarpone"), newer ones hold
 * `{name, quantity, note}` objects — there is no reprocess path, so the old
 * shape never ages out. Both fold to the same display line.
 */
function ingredientText(value: unknown): string | null {
  if (isRecord(value)) {
    const name = clean(value.name);
    if (!name) return null;
    const quantity = clean(value.quantity);
    const note = clean(value.note);
    return `${quantity ? `${quantity} ` : ''}${name}${note ? `, ${note}` : ''}`;
  }
  return clean(value);
}

function ingredientChips(value: unknown): DetailField | null {
  if (!Array.isArray(value)) return null;
  const items = value.map(ingredientText).filter((v): v is string => v !== null);
  return items.length ? { label: 'Ingredients', items, style: 'chips' } : null;
}

/**
 * The bespoke exercises layout: name as the card title, the prescription
 * (sets × reps · rest · tempo) as one compact meta line, cues and
 * alternatives as chip rows. Each exercise carries a `statePath` for
 * "mark complete" and a `restLabel` for the rest timer — Phase 4 §4.2.
 */
function exercisesField(value: unknown, itemStates: ItemStates): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  value.forEach((raw, i) => {
    if (!isRecord(raw)) return;
    const name = clean(raw.name);
    if (!name) return;
    const sets = clean(raw.sets);
    const reps = clean(raw.reps);
    const rows: DetailObjectRow[] = [];
    const cues = cleanList(raw.cues);
    if (cues.length) rows.push({ label: 'Cues', items: cues });
    const alternatives = cleanList(raw.alternatives);
    if (alternatives.length) rows.push({ label: 'Alternatives', items: alternatives });
    objects.push({
      title: name,
      meta: join([
        sets && reps ? `${sets} × ${reps}` : sets ? `${sets} sets` : reps,
        clean(raw.rest) ? `rest ${clean(raw.rest)}` : null,
        clean(raw.tempo) ? `tempo ${clean(raw.tempo)}` : null,
      ]),
      rows,
      statePath: `exercises[${i}]`,
      control: 'check',
      restLabel: clean(raw.rest) ?? undefined,
    });
  });
  return objects.length
    ? { label: 'Exercises', objects, style: 'objects', progress: checkProgress(objects, itemStates) }
    : null;
}

/**
 * The bespoke recommendation_list layout: rank + name as the card title,
 * kind/year as the meta line, `reason` promoted to its own row — per the
 * design doc, the reason a creator recommends something is the field a
 * watchlist entry is worthless without, so it must not get buried among
 * generic leftovers rows the way `objectListField` would render it.
 *
 * Each item carries a `statePath` and `control: 'watch'` for the
 * watched/rating control — Phase 4 §4.2, the flagship behavior. §5.2 adds a
 * watched-count progress bar on top, the same derivation `checkProgress`
 * already does for checklist ticks and course sections.
 */
function recommendationItemsField(
  value: unknown,
  itemStates: ItemStates,
  entityStates: EntityStates,
): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  value.forEach((raw, i) => {
    if (!isRecord(raw)) return;
    const name = clean(raw.name);
    if (!name) return;
    const rank = clean(raw.rank);
    const rows: DetailObjectRow[] = [];
    const reason = clean(raw.reason);
    if (reason) rows.push({ label: 'Why', text: reason });
    const platform = clean(raw.platform);
    if (platform) rows.push({ label: 'Where', text: platform });
    const genre = cleanList(raw.genre);
    if (genre.length) rows.push({ label: 'Genre', items: genre });
    objects.push({
      title: rank ? `${rank}. ${name}` : name,
      meta: join([clean(raw.kind), clean(raw.year)]),
      rows,
      statePath: `items[${i}]`,
      // K2: entity-keyed too, so watched/rating survives this same title
      // appearing in a later save — see `DetailObject.entityKey`.
      entityKey: computeEntityKey(clean(raw.kind), name),
      control: 'watch',
      imageUrl: clean(raw.posterUrl) ?? undefined,
    });
  });
  return objects.length
    ? { label: 'Items', objects, style: 'objects', progress: checkProgress(objects, itemStates, entityStates) }
    : null;
}

/**
 * The bespoke checklist layout: item text as the title, `detail` and
 * `optional` as supporting rows, each with a `control: 'check'` tick and a
 * progress bar computed from how many are done — Phase 4 §4.2, "the
 * smallest full loop" for proving the item-state mechanism.
 */
function checklistItemsField(value: unknown, itemStates: ItemStates): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  value.forEach((raw, i) => {
    if (!isRecord(raw)) return;
    const text = clean(raw.text);
    if (!text) return;
    const rows: DetailObjectRow[] = [];
    const detail = clean(raw.detail);
    if (detail) rows.push({ label: 'Detail', text: detail });
    objects.push({
      title: text,
      meta: clean(raw.optional)?.toLowerCase() === 'yes' ? 'optional' : undefined,
      rows,
      statePath: `items[${i}]`,
      control: 'check',
    });
  });
  return objects.length
    ? { label: 'Checklist', objects, style: 'objects', progress: checkProgress(objects, itemStates) }
    : null;
}

/**
 * The bespoke course layout: section name as the title, `covers`/`duration`
 * as meta/rows, each with a `control: 'check'` tick — "section done" feeds
 * both this progress bar and the Home Continue rail's finer-grained progress
 * (`docs/next-phases.md` §4.2).
 */
function sectionsField(value: unknown, itemStates: ItemStates): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  value.forEach((raw, i) => {
    if (!isRecord(raw)) return;
    const name = clean(raw.name);
    if (!name) return;
    const rows: DetailObjectRow[] = [];
    const covers = clean(raw.covers);
    if (covers) rows.push({ label: 'Covers', text: covers });
    objects.push({
      title: name,
      meta: clean(raw.duration) ?? undefined,
      rows,
      statePath: `sections[${i}]`,
      control: 'check',
    });
  });
  return objects.length
    ? { label: 'Sections', objects, style: 'objects', progress: checkProgress(objects, itemStates) }
    : null;
}

/**
 * The bespoke itinerary places layout: each place gets its own "Open in
 * Maps" deep link built from its name plus whichever locality it or the
 * itinerary as a whole carries — pure URL construction, no state
 * (`docs/next-phases.md` §4.2).
 */
function placesField(value: unknown, destination: string | null): DetailField | null {
  if (!Array.isArray(value)) return null;
  const records = value.filter((raw): raw is Record<string, unknown> => isRecord(raw) && !!clean(raw.name));
  if (!records.length) return null;

  const objects: DetailObject[] = records.map((raw) => {
    const name = clean(raw.name) as string;
    const rows: DetailObjectRow[] = [];
    const tips = cleanList(raw.tips);
    if (tips.length) rows.push({ label: 'Tips', items: tips });
    const cost = clean(raw.cost);
    if (cost) rows.push({ label: 'Cost', text: cost });
    const timeNeeded = clean(raw.timeNeeded);
    if (timeNeeded) rows.push({ label: 'Time needed', text: timeNeeded });
    const area = clean(raw.area);
    const query = area ? `${name}, ${area}` : destination ? `${name}, ${destination}` : name;
    // `day` is left out here: when clustering applies below, it's already
    // the group heading; when it doesn't (every place shares one day, or
    // none states one), repeating it per row said nothing the field's own
    // "Places" heading and context hadn't already.
    return {
      title: name,
      meta: join([clean(raw.kind), area]),
      rows,
      mapsUrl: mapsUrl(query),
    };
  });

  // Cluster into "Day 1–3" / "Day 4" the way an itinerary is actually read —
  // but only when clustering says something: a single day (or none at all)
  // would produce one group repeating the field's own heading, so the flat
  // list stays the fallback. Grouped by `day`, not `area`: the registry
  // defines a place's `area` as the neighbourhood *within* it, not the
  // region it belongs to, so it names a place's own detail, not a group of
  // places — see `placeGroups.ts`.
  const dayGroups = groupByDay(records.map((r) => ({ day: clean(r.day) })));
  const groups: DetailFieldGroup[] | undefined =
    dayGroups.length > 1
      ? dayGroups.map((g) => ({ label: g.label, objects: g.indices.map((i) => objects[i]) }))
      : undefined;

  return { label: 'Places', objects, groups, style: 'objects' };
}

/**
 * The generic shape for a nested object array the client has no bespoke
 * layout for — the same promise `leftovers` makes for flat fields, extended
 * one level down: a new objectArray field in the registry renders as
 * sub-cards instead of silently vanishing.
 */
function objectListField(label: string, value: unknown): DetailField | null {
  if (!Array.isArray(value)) return null;
  const objects: DetailObject[] = [];
  for (const raw of value) {
    if (!isRecord(raw)) continue;
    const titleKey = clean(raw.name) ? 'name' : clean(raw.title) ? 'title' : null;
    const rows: DetailObjectRow[] = [];
    for (const [key, v] of Object.entries(raw)) {
      if (key === titleKey) continue;
      if (Array.isArray(v)) {
        const items = cleanList(v);
        if (items.length) rows.push({ label: humanise(key), items });
      } else {
        const text = clean(v);
        if (text) rows.push({ label: humanise(key), text });
      }
    }
    if (titleKey || rows.length) {
      objects.push({ title: titleKey ? (clean(raw[titleKey]) as string) : undefined, rows });
    }
  }
  return objects.length ? { label, objects, style: 'objects' } : null;
}

/**
 * Fields the detail view must never render as content — they are either shown
 * elsewhere (the title, the meta line), they are bookkeeping, or (`tmdbId`,
 * `tmdbUrl`, `posterUrl`, `thumbnailUrl`, `latitude`, `longitude`) they are
 * enrichment's own internal plumbing: a numeric TMDB id or a raw storage URL
 * has no value to a reader even disclosed, where `openingHours` or a page
 * count does — so those fall through to `leftovers()` and the "More
 * details" disclosure instead of being suppressed outright.
 */
const HANDLED_ELSEWHERE: Record<string, ReadonlySet<string>> = {
  recipe: new Set(['title', 'servings', 'prepTime', 'cookTime', 'cuisine', 'ingredients', 'steps', 'dietaryNotes']),
  movie: new Set(['title', 'year', 'director', 'genre', 'rating', 'synopsis', 'whereTo', 'tmdbId', 'tmdbUrl', 'posterUrl']),
  place: new Set(['name', 'type', 'address', 'cuisine', 'priceRange', 'highlights', 'rating', 'latitude', 'longitude']),
  workout: new Set([
    'title', 'summary', 'category', 'goal', 'muscleGroups', 'duration', 'difficulty',
    'equipment', 'warmup', 'exercises', 'cooldown', 'progression', 'warnings',
    'estimatedDurationMin', 'estimatedDifficulty',
  ]),
  book: new Set(['title', 'thumbnailUrl']),
  other: new Set(['title', 'summary', 'tags']),
  unusable: new Set(['reason']),
  recommendation_list: new Set(['title', 'medium', 'summary', 'items', 'orderMatters', 'suggestedOrder']),
  checklist: new Set(['title', 'summary', 'context', 'items']),
  course: new Set(['title', 'subject', 'level', 'summary', 'sections', 'prerequisites', 'resources', 'outcomes']),
  itinerary: new Set(['title', 'destination', 'durationDays', 'summary', 'bestSeason', 'places', 'generalTips']),
};

/**
 * Anything the model returned that the bespoke layout did not claim.
 *
 * This is what stops a new knowledge type — or a new field on an existing one —
 * from silently vanishing from the UI. The registry is a *data* change
 * server-side by design (CLAUDE.md), so the client must not require a matching
 * code change to show what it produces; it just shows the extra fields
 * generically until someone gives them a nicer home.
 *
 * Every result is `secondary: true` — a field reaching the screen only
 * because nothing claimed it is, by definition, not one a type's own bespoke
 * branch considered essential. The screen renders these behind a "More
 * details" disclosure rather than at the same priority as Ingredients or
 * Genre.
 */
function leftovers(data: Record<string, unknown>, knowledgeType: string): DetailField[] {
  const claimed = HANDLED_ELSEWHERE[knowledgeType] ?? new Set<string>();
  return compact(
    Object.entries(data)
      .filter(([key]) => !claimed.has(key))
      .map(([key, value]) =>
        Array.isArray(value) && value.some(isRecord)
          ? objectListField(humanise(key), value)
          : Array.isArray(value)
            ? listField(humanise(key), value)
            : textField(humanise(key), value),
      ),
  ).map((field) => ({ ...field, secondary: true }));
}

/**
 * The full-page view of a save, as opposed to `buildCardModel`'s one-row
 * summary — same source data, different question. The card asks "what is this,
 * at a glance"; this asks "show me everything the model found".
 *
 * Returns `null` when there is nothing structured to show, which the screen
 * renders as a status page rather than an empty one: a save that is still
 * processing or that failed is a legitimate thing to open, and it must explain
 * itself instead of looking broken.
 */
export function buildDetailModel(save: SaveResponse, entityStates?: EntityStates): SaveDetailModel | null {
  if (save.status !== 'ready' || !save.knowledgeType || !save.structuredData) return null;
  const d = save.structuredData;
  const type = save.knowledgeType;

  switch (type) {
    case 'recipe': {
      const title = clean(d.title);
      if (!title) return null;
      const servings = clean(d.servings);
      return {
        title,
        meta: join([
          servings ? `${servings} servings` : null,
          clean(d.prepTime) ? `${clean(d.prepTime)} prep` : null,
          clean(d.cookTime) ? `${clean(d.cookTime)} cook` : null,
          clean(d.cuisine),
        ]),
        fields: [
          ...compact([
            ingredientChips(d.ingredients),
            listField('Steps', d.steps, 'steps'),
            listField('Dietary notes', d.dietaryNotes),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'workout': {
      const title = clean(d.title);
      if (!title) return null;
      // §5.1: `estimatedDurationMin`/`estimatedDifficulty` are the model's own
      // judgement, not something the content stated — shown only when the
      // real field is unclear, and always suffixed "(estimated)" so it can
      // never be mistaken for a fact the creator said (docs/next-phases.md
      // §5.1's "distinguishable from extracted ones" rule).
      const durationEstimate =
        !clean(d.duration) && clean(d.estimatedDurationMin)
          ? `${clean(d.estimatedDurationMin)} min (estimated)`
          : null;
      const difficultyEstimate =
        !clean(d.difficulty) && clean(d.estimatedDifficulty)
          ? `${clean(d.estimatedDifficulty)} (estimated)`
          : null;
      return {
        title,
        meta: join([clean(d.duration), clean(d.category), clean(d.goal), clean(d.difficulty)]),
        lede: clean(d.summary) ?? undefined,
        fields: [
          ...compact([
            listField('Muscle groups', d.muscleGroups),
            listField('Equipment', d.equipment),
            listField('Warm-up', d.warmup, 'steps'),
            workoutLoadField(d.exercises, clean(d.duration)),
            textField('Est. duration', durationEstimate),
            textField('Est. difficulty', difficultyEstimate),
            exercisesField(d.exercises, save.itemStates),
            listField('Cool-down', d.cooldown, 'steps'),
            textField('Progression', d.progression),
            listField('Watch out', d.warnings),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'movie': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        meta: join([clean(d.year), clean(d.director), cleanList(d.genre).join(', ') || null]),
        lede: clean(d.synopsis) ?? undefined,
        fields: [
          ...compact([
            listField('Genre', d.genre),
            textField('Rating', d.rating),
            textField('Where to watch', d.whereTo),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'place': {
      // The registry names this `name`, not `title`.
      const title = clean(d.name);
      if (!title) return null;
      const address = clean(d.address);
      return {
        title,
        meta: join([clean(d.type), clean(d.cuisine), clean(d.priceRange)]),
        fields: [
          ...compact([
            textField('Address', d.address),
            listField('Highlights', d.highlights),
            textField('Rating', d.rating),
          ]),
          ...leftovers(d, type),
        ],
        // Pure URL construction, no state — Phase 4 §4.2.
        mapsUrl: mapsUrl(address ? `${title}, ${address}` : title),
      };
    }

    case 'other': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        lede: clean(d.summary) ?? undefined,
        fields: [...compact([listField('Tags', d.tags)]), ...leftovers(d, type)],
      };
    }

    case 'recommendation_list': {
      const title = clean(d.title);
      if (!title) return null;
      // §5.1: `suggestedOrder` is the model's own suggestion (only populated
      // when `orderMatters` is 'no'), never the creator's — the label says
      // "Suggested" rather than "Order", and the field is skipped entirely
      // when the creator did prescribe one, so the two are never confusable.
      const suggestedOrder =
        clean(d.orderMatters) !== 'yes' ? cleanList(d.suggestedOrder) : [];
      return {
        title,
        meta: join([clean(d.medium), clean(d.orderMatters) === 'yes' ? 'watch in order' : null]),
        lede: clean(d.summary) ?? undefined,
        fields: [
          ...compact([
            recommendationItemsField(d.items, save.itemStates, entityStates),
            suggestedOrder.length
              ? { label: 'Suggested order (estimated)', items: suggestedOrder, style: 'chips' as const }
              : null,
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'checklist': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        lede: clean(d.summary) ?? clean(d.context) ?? undefined,
        fields: [...compact([checklistItemsField(d.items, save.itemStates)]), ...leftovers(d, type)],
      };
    }

    case 'course': {
      const title = clean(d.title);
      if (!title) return null;
      return {
        title,
        meta: join([clean(d.subject), clean(d.level)]),
        lede: clean(d.summary) ?? undefined,
        fields: [
          ...compact([
            sectionsField(d.sections, save.itemStates),
            listField('Prerequisites', d.prerequisites),
            listField('Resources', d.resources),
            listField('Outcomes', d.outcomes),
          ]),
          ...leftovers(d, type),
        ],
      };
    }

    case 'itinerary': {
      const title = clean(d.title);
      if (!title) return null;
      const destination = clean(d.destination);
      return {
        title,
        meta: join([
          destination,
          clean(d.durationDays) ? `${clean(d.durationDays)} days` : null,
          clean(d.bestSeason),
        ]),
        lede: clean(d.summary) ?? undefined,
        fields: [
          ...compact([placesField(d.places, destination), listField('General tips', d.generalTips)]),
          ...leftovers(d, type),
        ],
      };
    }

    default: {
      // A knowledge type the registry gained without this file changing. Show
      // it generically rather than nothing — see `leftovers`.
      const titleKey = clean(d.title) ? 'title' : clean(d.name) ? 'name' : null;
      if (!titleKey) return null;
      return {
        title: clean(d[titleKey]) as string,
        // Whichever key supplied the heading must not also render as a field
        // below it. Only the bespoke branches above have a static claim list,
        // so for an unknown type the claim has to be worked out here.
        fields: leftovers(d, type).filter((f) => f.label !== humanise(titleKey)),
      };
    }
  }
}
