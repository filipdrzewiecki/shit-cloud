-- Which app used which data, from the host page's X-ShitCloud-App header. Informational: it feeds warnings ("these
-- apps read what you are changing"), it guards nothing. Both foreign keys cascade, so deleting either app removes its
-- rows; rows unused for data.deps-ttl are removed by ExpiryJob.
create table app_deps (
    reader        text        not null references apps (slug) on delete cascade,
    space         text        not null references apps (slug) on delete cascade,
    collection    text        not null,
    mode          text        not null check (mode in ('read', 'write')),
    first_seen_at timestamptz not null,
    last_seen_at  timestamptz not null,
    primary key (space, collection, mode, reader)  -- every lookup is "who uses this space"
);
create index app_deps_last_seen_at on app_deps (last_seen_at);
-- The cascade from apps (slug) as reader.
create index app_deps_reader on app_deps (reader);
