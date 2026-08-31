-- V22: Push notification tokens for device-targeted push delivery (e.g. Space activity).
-- References auth.users(id) so tokens are automatically cleaned up on account deletion.

create table if not exists user_push_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    token text not null,
    platform text not null default 'expo',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, token)
);

create index if not exists idx_user_push_tokens_user on user_push_tokens(user_id);
