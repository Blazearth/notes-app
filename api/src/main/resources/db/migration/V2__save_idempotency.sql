-- POST /v1/saves is retried by design once the share extension ships: its
-- background URLSession retries on the caller's schedule, not the server's,
-- so a retried request must land on the same save rather than creating a
-- second one. The client supplies an Idempotency-Key header once per share
-- attempt and replays it verbatim across retries.
--
-- Nullable, because not every caller sends one (the app's own Paste Link
-- tile, for instance) — those saves simply get no dedupe protection. The
-- partial unique index only constrains rows that do carry a key; NULLs never
-- collide with each other under a plain unique index either, but the WHERE
-- clause keeps the index small and makes the intent explicit.
alter table saves
    add column idempotency_key text;

create unique index saves_user_idempotency_key_idx
    on saves (user_id, idempotency_key)
    where idempotency_key is not null;
