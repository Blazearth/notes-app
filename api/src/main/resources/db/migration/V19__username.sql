-- V19: usernames
--
-- username        — the canonical display form chosen by the user (e.g. BlazeArth).
--                   Nullable so existing rows are not broken by this migration.
-- username_lower  — generated column for case-insensitive uniqueness check.
--
-- The unique index on username_lower means two users cannot hold
-- 'blaze' and 'Blaze' simultaneously.

alter table profiles
    add column if not exists username       text,
    add column if not exists username_lower text generated always as (lower(username)) stored;

-- Enforce uniqueness on the canonical form and on the lower-cased form.
create unique index if not exists profiles_username_idx       on profiles (username)       where username is not null;
create unique index if not exists profiles_username_lower_idx on profiles (username_lower) where username_lower is not null;
