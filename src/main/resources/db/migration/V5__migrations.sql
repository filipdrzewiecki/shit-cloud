-- Data migrations written by the build chat: what was proposed, checked and applied (the audit), and while undo is
-- possible a frozen copy of the documents as they were before. A migration converts documents in place, in the
-- app's own space; the copy is removed by ExpiryJob once copy_until has passed.
create table migrations (
    id                  bigint generated always as identity primary key,
    space               text        not null references apps (slug) on delete cascade,
    conversation_id     text        references chat_conversations (id) on delete set null,
    collections         text[]      not null,
    summary             text,                   -- the model's one-line description of the change
    code                text        not null,   -- the transform, function migrate(doc, collection)
    status              text        not null check (status in ('proposed', 'applied', 'undone')),
    checked             jsonb       not null,   -- counts of the check run when proposed
    result              jsonb,                  -- counts of the run that applied it
    html_version_before integer,
    html_version_after  integer,
    proposed_by         text        not null,
    proposed_at         timestamptz not null,
    applied_by          text,
    applied_at          timestamptz,
    undone_by           text,
    undone_at           timestamptz,
    copy_until          timestamptz              -- the copy exists (and undo is possible) until then
);
create index migrations_space on migrations (space, id desc);
create index migrations_proposed_at on migrations (proposed_at) where status = 'proposed';

-- The migrated collections' documents as they were right before the migration was applied.
create table migration_docs (
    migration_id bigint      not null references migrations (id) on delete cascade,
    collection   text        not null,
    doc_id       text        not null,
    data         jsonb       not null,
    version      bigint      not null,
    created_by   text        not null,
    created_at   timestamptz not null,
    updated_by   text        not null,
    updated_at   timestamptz not null,
    primary key (migration_id, collection, doc_id)
);

-- History rows written by a migration and by undoing one.
alter table doc_history drop constraint doc_history_op_check;
alter table doc_history add constraint doc_history_op_check
    check (op in ('update', 'delete', 'migrate', 'undo'));
