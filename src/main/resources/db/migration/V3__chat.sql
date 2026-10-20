-- Build chat conversations, saved per builder so they can be continued later.
create table chat_conversations (
    id          text primary key,           -- "c_" + random, handed to the client in the first answer
    owner       text        not null,       -- the builder's id; nobody else sees the conversation
    title       text        not null,
    target_slug text,                       -- the app being reworked; null while creating a new one
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);
create index chat_conversations_owner on chat_conversations (owner, updated_at desc);

-- One row per message, in the shape the client renders (AI SDK UI message: id, role, parts). Drafts live in
-- the parts of the answer that produced them.
create table chat_messages (
    conversation_id text        not null references chat_conversations (id) on delete cascade,
    seq             integer     not null,
    id              text        not null,
    role            text        not null,  -- user | assistant
    parts           jsonb       not null,
    model           text,                  -- answers only: which model wrote it and what it cost
    input_tokens    integer,
    output_tokens   integer,
    created_at      timestamptz not null,
    primary key (conversation_id, seq)
);
