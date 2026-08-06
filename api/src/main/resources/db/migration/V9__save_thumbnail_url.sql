-- V9 — store the thumbnail URL extracted by the pipeline
-- Nullable: saves processed before this migration and text/image saves have none.
alter table saves add column if not exists thumbnail_url text;
