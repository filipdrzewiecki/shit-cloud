-- The app registry: one row per app with its current HTML; every deploy also lands in app_versions.
create table apps (
    slug         text primary key,
    name         text        not null,
    description  text,
    html         text        not null,
    owner        text        not null,  -- who created the app; a redeploy by someone else keeps it
    version      integer     not null,
    last_used_at timestamptz not null,
    archived     boolean     not null default false,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    updated_by   text        not null   -- who deployed the current version
);
create index apps_last_used_at on apps (last_used_at desc);

-- Removed by ExpiryJob after apps.version-ttl.
create table app_versions (
    id          bigint generated always as identity primary key,
    app_slug    text        not null references apps (slug) on delete cascade,
    version     integer     not null,
    name        text        not null,
    description text,
    html        text        not null,
    author      text        not null,
    created_at  timestamptz not null,
    unique (app_slug, version)
);
create index app_versions_created_at on app_versions (created_at);

-- App data. space = the app's slug; the app's own fields are the JSONB `data`, so they can never collide with
-- or reach the platform's columns. The unique index also serves every query: space and collection come first.
create table docs (
    id         bigint generated always as identity primary key,
    space      text        not null references apps (slug) on delete cascade,
    collection text        not null,
    doc_id     text        not null,
    data       jsonb       not null,
    version    bigint      not null,
    created_by text        not null,
    created_at timestamptz not null,
    updated_by text        not null,
    updated_at timestamptz not null,
    unique (space, collection, doc_id)
);

-- A document's earlier states, written in the same transaction as the change that replaced them. version, data,
-- updated_by and updated_at describe that earlier state; changed_by / changed_at the change. No foreign key:
-- history outlives a deleted app until ExpiryJob removes it after data.history-ttl.
create table doc_history (
    id         bigint generated always as identity primary key,
    space      text        not null,
    collection text        not null,
    doc_id     text        not null,
    version    bigint      not null,
    op         text        not null check (op in ('update', 'delete')),
    data       jsonb       not null,
    updated_by text        not null,
    updated_at timestamptz not null,
    changed_by text        not null,
    changed_at timestamptz not null
);
create index doc_history_doc on doc_history (space, collection, doc_id, version);
create index doc_history_changed_at on doc_history (changed_at);
