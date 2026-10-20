-- Who archived an app and when; null while it is live. An archived app keeps its data, still opens (with a
-- Restore bar) and is hidden from the list until restored.
alter table apps
    add column archived_at timestamptz,
    add column archived_by text;
