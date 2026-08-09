/**
 * The store, on real SQLite (native only).
 *
 * Metro resolves `sqliteStore.web.ts` on web, where this file is never bundled
 * — see that file for why. `@/local/index` falls back to `memoryStore` if
 * opening this throws, so a device that cannot open the database degrades to a
 * session-scoped cache rather than to a broken app.
 *
 * `enableChangeListener` is on because L4's sync writes and the UI's reads will
 * eventually live on different sides of a transaction; the change *bus* is
 * still ours (`ChangeBus`), because it is table-scoped and fires synchronously
 * after a write, which is what `useLive` needs and what the native listener's
 * row-level, post-commit callback does not give.
 */

import * as SQLite from 'expo-sqlite';

import type {
  SaveResponse,
  ShoppingListItem,
  ShoppingListResponse,
  Space,
  SpaceMember,
} from '@/api/types';
import { isLocalId, type OutboxEntry, type OutboxOp, type OutboxPayloads } from './outbox';
import { KV, SCHEMA_SQL, SCHEMA_VERSION, type StoreTable } from './schema';
import { ChangeBus, fromRow, toRow, type FeedQuery, type LocalStore } from './store';

const DATABASE_NAME = 'weavr.db';

/** Every table the store owns, for the drop-and-recreate on a schema bump. */
const ALL_TABLES = [
  'saves', 'item_states', 'entity_states', 'overrides',
  'spaces', 'space_members', 'shopping_items', 'outbox', 'kv',
] as const;

const CLEAR_ALL_SQL = ALL_TABLES.map((table) => `delete from ${table};`).join(' ');

interface JsonRow {
  json: string;
}

interface OutboxRow {
  id: number;
  op: string;
  payload: string;
  idempotency_key: string;
  entity_id: string | null;
  created_at: string;
  attempts: number;
  next_attempt_at: string | null;
  last_error: string | null;
  status: string;
}

function toOutboxEntry(row: OutboxRow): OutboxEntry {
  return {
    id: row.id,
    op: row.op as OutboxOp,
    payload: JSON.parse(row.payload) as OutboxPayloads[OutboxOp],
    idempotencyKey: row.idempotency_key,
    entityId: row.entity_id,
    createdAt: row.created_at,
    attempts: row.attempts,
    nextAttemptAt: row.next_attempt_at,
    lastError: row.last_error,
    status: row.status === 'failed' ? 'failed' : 'pending',
  };
}

export function createSqliteStore(): LocalStore {
  const bus = new ChangeBus();
  let db: SQLite.SQLiteDatabase | null = null;

  function handle(): SQLite.SQLiteDatabase {
    if (!db) throw new Error('Local store used before open()');
    return db;
  }

  function touched(...tables: StoreTable[]) {
    bus.emit(...tables);
  }

  /** Every save's item states in one read — the feed needs all of them at once. */
  async function itemStatesFor(saveIds: string[]): Promise<Map<string, Record<string, Record<string, unknown>>>> {
    const out = new Map<string, Record<string, Record<string, unknown>>>();
    if (saveIds.length === 0) return out;
    const rows = await handle().getAllAsync<{ save_id: string; item_path: string; json: string }>(
      'select save_id, item_path, json from item_states',
    );
    const wanted = new Set(saveIds);
    for (const row of rows) {
      if (!wanted.has(row.save_id)) continue;
      const bucket = out.get(row.save_id) ?? {};
      try {
        bucket[row.item_path] = JSON.parse(row.json) as Record<string, unknown>;
      } catch {
        // One unreadable state row must not sink the whole feed read.
        continue;
      }
      out.set(row.save_id, bucket);
    }
    return out;
  }

  async function writeSave(save: SaveResponse) {
    const row = toRow(save);
    await handle().runAsync(
      `insert into saves
         (id, created_at, updated_at, status, knowledge_type, space_id, lifecycle_status, favorite, archived, title, json)
       values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       on conflict(id) do update set
         created_at = excluded.created_at,
         updated_at = excluded.updated_at,
         status = excluded.status,
         knowledge_type = excluded.knowledge_type,
         space_id = excluded.space_id,
         lifecycle_status = excluded.lifecycle_status,
         favorite = excluded.favorite,
         archived = excluded.archived,
         title = excluded.title,
         json = excluded.json`,
      row.id,
      row.createdAt,
      row.updatedAt,
      row.status,
      row.knowledgeType,
      row.spaceId,
      row.lifecycleStatus,
      row.favorite,
      row.archived,
      row.title,
      row.json,
    );
    if (save.itemStates) await writeItemStates(save.id, save.itemStates);
  }

  async function writeItemStates(saveId: string, states: Record<string, Record<string, unknown>>) {
    await handle().runAsync('delete from item_states where save_id = ?', saveId);
    for (const [itemPath, state] of Object.entries(states)) {
      await handle().runAsync(
        'insert or replace into item_states (save_id, item_path, json) values (?, ?, ?)',
        saveId,
        itemPath,
        JSON.stringify(state),
      );
    }
  }

  return {
    kind: 'sqlite',

    async open() {
      if (db) return;
      db = await SQLite.openDatabaseAsync(DATABASE_NAME, { enableChangeListener: true });
      await db.execAsync('pragma journal_mode = WAL;');
      await db.execAsync(SCHEMA_SQL);
      // A schema bump drops rather than migrates: everything here is a cache of
      // something the server can re-send, so re-fetching is strictly cheaper
      // than a migration path that has to stay correct forever.
      const stored = await db.getFirstAsync<{ value: string }>('select value from kv where key = ?', KV.schemaVersion);
      const version = stored ? (JSON.parse(stored.value) as number) : null;
      if (version !== SCHEMA_VERSION) {
        await db.execAsync(CLEAR_ALL_SQL);
        await db.runAsync(
          'insert or replace into kv (key, value) values (?, ?)',
          KV.schemaVersion,
          JSON.stringify(SCHEMA_VERSION),
        );
      }
    },

    async wipe() {
      // The outbox goes with everything else: it holds writes made *as the
      // previous user*, and replaying them under a new session would attribute
      // one person's edits to another.
      await handle().execAsync(CLEAR_ALL_SQL);
      await handle().runAsync(
        'insert or replace into kv (key, value) values (?, ?)',
        KV.schemaVersion,
        JSON.stringify(SCHEMA_VERSION),
      );
      bus.emit(...ALL_TABLES);
    },

    // ------------------------------------------------------------- saves

    async putSaves(incoming, options) {
      await handle().withTransactionAsync(async () => {
        if (options?.replaceAll) {
          const ids = incoming.map((s) => s.id);
          const existing = await handle().getAllAsync<{ id: string }>('select id from saves');
          const keep = new Set(ids);
          for (const row of existing) {
            // A `local:` row is a save the server has never heard of, so a full
            // pull cannot mention it — reaping it would delete the user's save
            // and orphan the outbox entry that creates it.
            if (!keep.has(row.id) && !isLocalId(row.id)) {
              await handle().runAsync('delete from saves where id = ?', row.id);
              await handle().runAsync('delete from item_states where save_id = ?', row.id);
            }
          }
        }
        for (const save of incoming) await writeSave(save);
      });
      touched('saves', 'item_states');
    },

    async patchSave(id, changes) {
      const row = await handle().getFirstAsync<JsonRow>('select json from saves where id = ?', id);
      if (!row) return;
      const { itemStates: incomingStates, ...rest } = changes;
      const merged = { ...(JSON.parse(row.json) as SaveResponse), ...rest };
      await handle().withTransactionAsync(async () => {
        await writeSave(merged);
        if (incomingStates) await writeItemStates(id, incomingStates);
      });
      touched('saves', 'item_states');
    },

    async removeSave(id) {
      await handle().withTransactionAsync(async () => {
        await handle().runAsync('delete from saves where id = ?', id);
        await handle().runAsync('delete from item_states where save_id = ?', id);
      });
      touched('saves', 'item_states');
    },

    async readFeed(query: FeedQuery = {}) {
      const order = (query.orderBy ?? 'created') === 'updated' ? 'updated_at' : 'created_at';
      const where: string[] = [];
      const params: (string | number | null)[] = [];
      if (!query.includeArchived) where.push('archived = 0');
      if (query.spaceId !== undefined) {
        if (query.spaceId === null) where.push('space_id is null');
        else {
          where.push('space_id = ?');
          params.push(query.spaceId);
        }
      }
      if (query.knowledgeType !== undefined) {
        where.push('knowledge_type = ?');
        params.push(query.knowledgeType);
      }
      const sql =
        `select json from saves` +
        (where.length ? ` where ${where.join(' and ')}` : '') +
        ` order by ${order} desc, id desc` +
        (query.limit !== undefined ? ` limit ${Number(query.limit)}` : '');
      const rows = await handle().getAllAsync<JsonRow>(sql, ...params);
      const saves = rows.map((row) => fromRow(row));
      const states = await itemStatesFor(saves.map((s) => s.id));
      return saves.map((save) => {
        const bucket = states.get(save.id);
        return bucket ? { ...save, itemStates: bucket } : save;
      });
    },

    async readSave(id) {
      const row = await handle().getFirstAsync<JsonRow>('select json from saves where id = ?', id);
      if (!row) return null;
      const states = await itemStatesFor([id]);
      return fromRow(row, states.get(id));
    },

    async readSavesByIds(ids) {
      if (ids.length === 0) return [];
      const rows = await handle().getAllAsync<JsonRow & { id: string }>('select id, json from saves');
      const byId = new Map(rows.map((row) => [row.id, row] as const));
      const states = await itemStatesFor(ids);
      const out: SaveResponse[] = [];
      for (const id of ids) {
        const row = byId.get(id);
        if (row) out.push(fromRow(row, states.get(id)));
      }
      return out;
    },

    async countByType() {
      const rows = await handle().getAllAsync<{ knowledge_type: string | null; n: number }>(
        `select knowledge_type, count(*) as n from saves
          where archived = 0 and status = 'ready' and knowledge_type is not null
          group by knowledge_type order by n desc`,
      );
      return rows.filter((r) => r.knowledge_type !== null).map((r) => [r.knowledge_type as string, r.n] as [string, number]);
    },

    async putItemStates(saveId, states) {
      await handle().withTransactionAsync(() => writeItemStates(saveId, states));
      touched('item_states');
    },

    async putItemState(saveId, itemPath, state) {
      await handle().runAsync(
        'insert or replace into item_states (save_id, item_path, json) values (?, ?, ?)',
        saveId,
        itemPath,
        JSON.stringify(state),
      );
      touched('item_states');
    },

    async removeItemState(saveId, itemPath) {
      await handle().runAsync(
        'delete from item_states where save_id = ? and item_path = ?',
        saveId,
        itemPath,
      );
      touched('item_states');
    },

    async reconcileSaveId(localId, real) {
      await handle().withTransactionAsync(async () => {
        // Item states first, while the old id still exists to move them off.
        await handle().runAsync(
          'update or replace item_states set save_id = ? where save_id = ?',
          real.id,
          localId,
        );
        await handle().runAsync('delete from saves where id = ?', localId);
        await writeSave(real);
        await handle().runAsync('update saves set pending = 0 where id = ?', real.id);
        // The outbox rewrite is textual for the same reason `rewriteLocalId` is:
        // an op added later cannot forget to participate, and a local id is a
        // uuid behind a prefix that appears in no other stored value.
        await handle().runAsync(
          `update outbox
             set entity_id = case when entity_id = ? then ? else entity_id end,
                 payload   = replace(payload, ?, ?)`,
          localId,
          real.id,
          localId,
          real.id,
        );
      });
      touched('saves', 'item_states', 'outbox');
    },

    // ------------------------------------------------------ entity state

    async putEntityStates(states, options) {
      await handle().withTransactionAsync(async () => {
        if (options?.replaceAll) await handle().runAsync('delete from entity_states');
        for (const [key, value] of Object.entries(states)) {
          await handle().runAsync(
            'insert or replace into entity_states (entity_key, json) values (?, ?)',
            key,
            JSON.stringify(value),
          );
        }
      });
      touched('entity_states');
    },

    async readEntityStates() {
      const rows = await handle().getAllAsync<{ entity_key: string; json: string }>(
        'select entity_key, json from entity_states',
      );
      const out: Record<string, Record<string, unknown>> = {};
      for (const row of rows) {
        try {
          out[row.entity_key] = JSON.parse(row.json) as Record<string, unknown>;
        } catch {
          continue;
        }
      }
      return out;
    },

    async removeEntityState(entityKey) {
      await handle().runAsync('delete from entity_states where entity_key = ?', entityKey);
      touched('entity_states');
    },

    // -------------------------------------------------- collection overrides

    async putOverrides(rows, options) {
      await handle().withTransactionAsync(async () => {
        if (options?.replaceAll) await handle().runAsync('delete from overrides');
        for (const row of rows) {
          await handle().runAsync(
            'insert or replace into overrides (override_type, subject_key, payload) values (?, ?, ?)',
            row.overrideType,
            row.subjectKey,
            JSON.stringify(row.payload),
          );
        }
      });
      touched('overrides');
    },

    async readOverrides() {
      const rows = await handle().getAllAsync<{
        override_type: string;
        subject_key: string;
        payload: string;
      }>('select override_type, subject_key, payload from overrides');
      const out: { overrideType: string; subjectKey: string; payload: Record<string, unknown> }[] = [];
      for (const row of rows) {
        try {
          out.push({
            overrideType: row.override_type,
            subjectKey: row.subject_key,
            payload: JSON.parse(row.payload) as Record<string, unknown>,
          });
        } catch {
          // One unreadable override costs that one rule, not the whole tree.
          continue;
        }
      }
      return out;
    },

    async removeOverride(overrideType, subjectKey) {
      await handle().runAsync(
        'delete from overrides where override_type = ? and subject_key = ?',
        overrideType,
        subjectKey,
      );
      touched('overrides');
    },

    // ------------------------------------------------------------ spaces

    async putSpaces(incoming, options) {
      await handle().withTransactionAsync(async () => {
        if (options?.replaceAll) {
          const keep = new Set(incoming.map((s) => s.id));
          const existing = await handle().getAllAsync<{ id: string }>('select id from spaces');
          for (const row of existing) {
            if (!keep.has(row.id)) {
              await handle().runAsync('delete from spaces where id = ?', row.id);
              await handle().runAsync('delete from space_members where space_id = ?', row.id);
            }
          }
        }
        for (const space of incoming) {
          await handle().runAsync(
            'insert or replace into spaces (id, last_activity_at, json) values (?, ?, ?)',
            space.id,
            space.lastActivityAt ?? null,
            JSON.stringify(space),
          );
        }
      });
      touched('spaces');
    },

    async removeSpace(id) {
      await handle().withTransactionAsync(async () => {
        await handle().runAsync('delete from spaces where id = ?', id);
        await handle().runAsync('delete from space_members where space_id = ?', id);
      });
      touched('spaces', 'space_members');
    },

    async readSpaces() {
      const rows = await handle().getAllAsync<JsonRow>('select json from spaces order by last_activity_at desc');
      return rows.map((row) => JSON.parse(row.json) as Space);
    },

    async readSpace(id) {
      const row = await handle().getFirstAsync<JsonRow>('select json from spaces where id = ?', id);
      return row ? (JSON.parse(row.json) as Space) : null;
    },

    async putSpaceMembers(spaceId, members) {
      await handle().withTransactionAsync(async () => {
        await handle().runAsync('delete from space_members where space_id = ?', spaceId);
        for (const member of members) {
          await handle().runAsync(
            'insert or replace into space_members (space_id, user_id, json) values (?, ?, ?)',
            spaceId,
            member.userId,
            JSON.stringify(member),
          );
        }
      });
      touched('space_members');
    },

    async removeSpaceMember(spaceId, userId) {
      await handle().runAsync(
        'delete from space_members where space_id = ? and user_id = ?',
        spaceId,
        userId,
      );
      touched('space_members');
    },

    async readSpaceMembers(spaceId) {
      const rows = await handle().getAllAsync<JsonRow>(
        'select json from space_members where space_id = ? order by json',
        spaceId,
      );
      return rows.map((row) => JSON.parse(row.json) as SpaceMember);
    },

    async readAllSpaceMembers() {
      const rows = await handle().getAllAsync<{ space_id: string; json: string }>(
        'select space_id, json from space_members',
      );
      const out: Record<string, SpaceMember[]> = {};
      for (const row of rows) {
        (out[row.space_id] ??= []).push(JSON.parse(row.json) as SpaceMember);
      }
      return out;
    },

    // ---------------------------------------------------- shopping list

    async putShoppingList(list: ShoppingListResponse) {
      await handle().withTransactionAsync(async () => {
        await handle().runAsync('delete from shopping_items');
        // `position` preserves the server's aisle-then-name order verbatim —
        // re-sorting here would walk the shopper through the shop twice.
        let position = 0;
        for (const item of list.items) {
          await handle().runAsync(
            'insert or replace into shopping_items (id, position, json) values (?, ?, ?)',
            item.id,
            position,
            JSON.stringify(item),
          );
          position += 1;
        }
        await handle().runAsync(
          'insert or replace into kv (key, value) values (?, ?)',
          KV.shoppingCategories,
          JSON.stringify(list.categories),
        );
      });
      touched('shopping_items', 'kv');
    },

    async patchShoppingItem(id, changes: Partial<ShoppingListItem>) {
      const row = await handle().getFirstAsync<JsonRow>('select json from shopping_items where id = ?', id);
      if (!row) return;
      const merged = { ...(JSON.parse(row.json) as ShoppingListItem), ...changes };
      await handle().runAsync('update shopping_items set json = ? where id = ?', JSON.stringify(merged), id);
      touched('shopping_items');
    },

    async removeShoppingItems(ids) {
      await handle().withTransactionAsync(async () => {
        for (const id of ids) await handle().runAsync('delete from shopping_items where id = ?', id);
      });
      touched('shopping_items');
    },

    async readShoppingList() {
      const rows = await handle().getAllAsync<JsonRow>('select json from shopping_items order by position asc');
      const categories = await handle().getFirstAsync<{ value: string }>(
        'select value from kv where key = ?',
        KV.shoppingCategories,
      );
      return {
        items: rows.map((row) => JSON.parse(row.json) as ShoppingListItem),
        categories: categories ? (JSON.parse(categories.value) as string[]) : [],
      };
    },

    // ------------------------------------------------------------ outbox

    async enqueueOutbox(draft) {
      const result = await handle().runAsync(
        `insert into outbox (op, payload, idempotency_key, entity_id, created_at)
         values (?, ?, ?, ?, ?)`,
        draft.op,
        JSON.stringify(draft.payload),
        draft.idempotencyKey,
        draft.entityId,
        draft.createdAt,
      );
      touched('outbox');
      return {
        ...draft,
        id: result.lastInsertRowId,
        attempts: 0,
        nextAttemptAt: null,
        lastError: null,
        status: 'pending',
      };
    },

    async readOutbox() {
      // `order by id` is the queue's order and the reason the column is an
      // autoincrement rather than a uuid — see `selectNext`.
      const rows = await handle().getAllAsync<OutboxRow>('select * from outbox order by id asc');
      const out: OutboxEntry[] = [];
      for (const row of rows) {
        try {
          out.push(toOutboxEntry(row));
        } catch {
          // An unparseable payload can never be sent, so leaving it pending
          // would block its entity forever. Mark it failed and move on; it
          // surfaces to the user like any other terminal failure.
          await handle().runAsync(
            "update outbox set status = 'failed', last_error = ? where id = ?",
            'This change could not be read back and was discarded.',
            row.id,
          );
        }
      }
      return out;
    },

    async updateOutbox(id, changes) {
      const sets: string[] = [];
      const params: (string | number | null)[] = [];
      if (changes.attempts !== undefined) {
        sets.push('attempts = ?');
        params.push(changes.attempts);
      }
      if (changes.nextAttemptAt !== undefined) {
        sets.push('next_attempt_at = ?');
        params.push(changes.nextAttemptAt);
      }
      if (changes.lastError !== undefined) {
        sets.push('last_error = ?');
        params.push(changes.lastError);
      }
      if (changes.status !== undefined) {
        sets.push('status = ?');
        params.push(changes.status);
      }
      if (changes.entityId !== undefined) {
        sets.push('entity_id = ?');
        params.push(changes.entityId);
      }
      if (changes.payload !== undefined) {
        sets.push('payload = ?');
        params.push(JSON.stringify(changes.payload));
      }
      if (sets.length === 0) return;
      params.push(id);
      await handle().runAsync(`update outbox set ${sets.join(', ')} where id = ?`, ...params);
      touched('outbox');
    },

    async removeOutbox(id) {
      await handle().runAsync('delete from outbox where id = ?', id);
      touched('outbox');
    },

    // ---------------------------------------------------------------- kv

    async putKv(key, value) {
      await handle().runAsync('insert or replace into kv (key, value) values (?, ?)', key, JSON.stringify(value));
      touched('kv');
    },

    async readKv<T>(key: string) {
      const row = await handle().getFirstAsync<{ value: string }>('select value from kv where key = ?', key);
      if (!row) return null;
      try {
        return JSON.parse(row.value) as T;
      } catch {
        return null;
      }
    },

    subscribe: (listener) => bus.subscribe(listener),
  };
}
