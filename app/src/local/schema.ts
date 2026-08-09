/**
 * The local schema, and the change-bus vocabulary that goes with it.
 *
 * The SQL below is what `sqliteStore` creates; `memoryStore` mirrors the same
 * tables as Maps. Both are addressed through the same {@link StoreTable} names,
 * which are also what `useLive` subscribes to — so a screen says *what it reads*
 * and gets re-run when exactly that changes, with no dependency on which
 * implementation is underneath.
 */

/**
 * Every table the store owns. Doubles as the change-bus channel set: a write
 * announces the tables it touched, and `useLive` re-runs the queries that named
 * one of them.
 */
export const STORE_TABLES = [
  'saves',
  'item_states',
  'entity_states',
  'overrides',
  'spaces',
  'space_members',
  'shopping_items',
  'outbox',
  'kv',
] as const;

export type StoreTable = (typeof STORE_TABLES)[number];

/**
 * Bumped whenever the DDL below changes shape. A bump drops and recreates.
 *
 * **2** — L3/L4: `outbox` and `overrides`. Dropping rather than migrating is
 * safe for `overrides` (a cache of something `GET /v1/sync` re-sends) and is
 * *not* free for `outbox`, which is the only table here holding writes the
 * server has never seen. That is a real cost, accepted once: at the moment of
 * the bump there is no outbox in the field to lose, and every future bump has to
 * weigh it again rather than assume the "everything here is a cache" rule still
 * holds. It no longer does.
 */
export const SCHEMA_VERSION = 2;

/**
 * `json` holds the full `SaveResponse` **minus `itemStates`** — see
 * `LocalStore.putSaves`. The denormalised columns beside it exist so the feed's
 * ordering and the Library's filters are index reads rather than a parse of
 * every row.
 *
 * `pending` marks a save with unsent local writes — set when an offline
 * `createSave` inserts a `local:<uuid>` row, cleared when the real id arrives.
 */
export const SCHEMA_SQL = `
create table if not exists saves (
  id               text primary key,
  created_at       text not null,
  updated_at       text not null,
  status           text,
  knowledge_type   text,
  space_id         text,
  lifecycle_status text,
  favorite         integer not null default 0,
  archived         integer not null default 0,
  title            text,
  json             text not null,
  pending          integer not null default 0
);
create index if not exists saves_created_idx on saves (created_at desc);
create index if not exists saves_space_idx   on saves (space_id);
create index if not exists saves_type_idx    on saves (knowledge_type);
create index if not exists saves_updated_idx on saves (updated_at desc);

create table if not exists item_states (
  save_id   text not null,
  item_path text not null,
  json      text not null,
  primary key (save_id, item_path)
);

create table if not exists entity_states (
  entity_key text primary key,
  json       text not null
);

-- K4's collection overrides (manual entity merge, entity rename, collection
-- rename). L2 deliberately did not hold these — no endpoint read them back, so
-- the local tree passed no-op overrides and nothing observable differed.
-- \`GET /v1/sync\` is that endpoint, so the local tree can now agree with the
-- server's on a renamed or manually merged entity.
create table if not exists overrides (
  override_type text not null,
  subject_key   text not null,
  payload       text not null,
  primary key (override_type, subject_key)
);

create table if not exists spaces (
  id               text primary key,
  last_activity_at text,
  json             text not null
);

create table if not exists space_members (
  space_id text not null,
  user_id  text not null,
  json     text not null,
  primary key (space_id, user_id)
);

create table if not exists shopping_items (
  id       text primary key,
  position integer not null,
  json     text not null
);

-- The outbox: every local write that has not reached the server yet.
--
-- \`op\` is a \`Repository\` method name and \`payload\` its arguments, so a queued
-- write is described in the same vocabulary the rest of the app uses rather than
-- as a serialised HTTP request — which would pin the queue to a URL shape that
-- can change under it.
--
-- \`idempotency_key\` is generated ONCE at enqueue and carried through every
-- retry. Generating it at send time would defeat the entire point: the case it
-- exists for is a request that reached the server and whose response was lost.
--
-- \`status\` is \`pending\` or \`failed\`. There is no \`sending\` — a row in flight
-- is tracked in memory for the length of one drain, because a process killed
-- mid-send must come back to a row that will be retried, not one stuck in a
-- state nothing clears.
create table if not exists outbox (
  id              integer primary key autoincrement,
  op              text not null,
  payload         text not null,
  idempotency_key text not null,
  entity_id       text,
  created_at      text not null,
  attempts        integer not null default 0,
  next_attempt_at text,
  last_error      text,
  status          text not null default 'pending'
);
create index if not exists outbox_status_idx on outbox (status, id);

create table if not exists kv (
  key   text primary key,
  value text not null
);
`;

/**
 * Keys in the `kv` table. Single-row server responses that have no interesting
 * shape to query over (`/me`, the weekly digest) live here rather than earning
 * a table each, alongside the store's own bookkeeping.
 */
export const KV = {
  /** Which user the stored rows belong to. A mismatch wipes the store. */
  ownerUserId: 'owner_user_id',
  /** Schema generation actually on disk, so a bump can drop and recreate. */
  schemaVersion: 'schema_version',
  /** `MeResponse`. */
  me: 'me',
  /** `DigestResponse` for the current week. */
  digest: 'digest',
  /** The aisle order that ships with the shopping list payload. */
  shoppingCategories: 'shopping_categories',
  /** Whether the shopping list has ever been fetched — empty vs. never-loaded. */
  syncedAt: (task: string) => `synced_at:${task}`,
  /**
   * The delta watermark: the `until` from the last **fully applied**
   * `GET /v1/sync` page.
   *
   * Always a value the *server* handed out, never `Date.now()`. Device clock
   * skew against Postgres would otherwise be a silent data-loss bug — a phone
   * running two seconds fast would step its cursor past rows it never received.
   */
  syncCursor: 'sync_cursor',
} as const;
