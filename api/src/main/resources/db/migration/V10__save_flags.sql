-- V10 — favorite and archived flags for the Library's swipe actions
-- Both boolean, both default false: existing saves are neither until touched.
alter table saves add column if not exists favorite boolean not null default false;
alter table saves add column if not exists archived boolean not null default false;
