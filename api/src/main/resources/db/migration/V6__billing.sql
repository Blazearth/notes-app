-- ---------------------------------------------------------------------------
-- V6 — the RevenueCat webhook, and what `subscriptions` needs to be correct
-- ---------------------------------------------------------------------------
-- V1 created `subscriptions` with (entitlement, status, renews_at) and left it
-- unwritten. Wiring the webhook to it turned up two things the original shape
-- could not express, both of which are about *ordering* rather than content.
--
--   * **Webhook deliveries are retried, and are not ordered.** RevenueCat
--     redelivers on any non-2xx and makes no ordering guarantee, so a RENEWAL
--     from 10:00 can arrive after the EXPIRATION from 10:05. Applying whatever
--     arrived last would then leave a lapsed user entitled — or worse, a paying
--     one locked out. `last_event_at` is the fix: an event older than the one
--     already applied is recorded and ignored.
--
--   * **Delivery is at-least-once, so the handler must be idempotent.**
--     `billing_events` is the dedupe: the event id is the primary key, and an
--     insert that conflicts means "already handled, ack and stop". It doubles
--     as an audit trail, which for the one table in this schema that decides
--     whether someone gets what they paid for is worth the rows.
--
-- Deliberately NOT here: a status enum. RevenueCat has a dozen event types and
-- adds more; `status` stays free text for the same reason `knowledge_type` does.
-- ---------------------------------------------------------------------------

alter table subscriptions
    add column product_id    text,
    add column store         text,
    add column environment   text,
    -- The event's own timestamp, not our clock. Guards against out-of-order
    -- delivery; see above.
    add column last_event_at timestamptz,
    add column last_event_id text;

-- "Which of my users are entitled right now" — the query the pipeline runs on
-- every metered call, so it should not be a sequential scan.
create index subscriptions_active_idx on subscriptions (status) where status = 'active';


-- Every webhook delivery, keyed by RevenueCat's own event id.
create table billing_events (
    id          text primary key,
    -- Null when the event's app_user_id is not one of ours: an anonymous
    -- RevenueCat id ($RCAnonymousID:...) from a purchase made before sign-in.
    -- Recorded rather than rejected, because a 4xx makes RevenueCat retry
    -- forever something that will never resolve.
    user_id     uuid references profiles (id) on delete set null,
    type        text        not null,
    -- The raw event, so a mis-parse is diagnosable after the fact without
    -- asking RevenueCat to redeliver.
    payload     jsonb       not null,
    -- Whether this delivery actually changed `subscriptions`, or was a
    -- duplicate / out-of-order / unattributable no-op.
    applied     boolean     not null default false,
    received_at timestamptz not null default now()
);

create index billing_events_user_idx on billing_events (user_id, received_at desc);


-- ---------------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------------
-- Internal, like `jobs` and `gemini_calls`: enabled with no policy, which is a
-- deny-all default to every client role. Only the API writes here, and it
-- connects with BYPASSRLS.
alter table billing_events enable row level security;
