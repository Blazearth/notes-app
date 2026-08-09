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
  'spaces',
  'space_members',
  'shopping_items',
  'kv',
] as const;

export type StoreTable = (typeof STORE_TABLES)[number];

/** Bumped whenever the DDL below changes shape. A bump drops and recreates. */
export const SCHEMA_VERSION = 1;

/**
 * `json` holds the full `SaveResponse` **minus `itemStates`** — see
 * `LocalStore.putSaves`. The denormalised columns beside it exist so the feed's
 * ordering and the Library's filters are index reads rather than a parse of
 * every row.
 *
 * `pending` is unused until L3 (the outbox) and is here so that phase is a
 * write path rather than a migration.
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
} as const;
