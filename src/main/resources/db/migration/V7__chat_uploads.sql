-- The HTML file a builder uploaded to have the build chat adapt it to the platform: the original, kept for the
-- conversation's "Before" view and as the base of the first edit. Goes with its conversation.
create table chat_uploads (
    conversation_id text        primary key references chat_conversations (id) on delete cascade,
    file_name       text        not null,
    html            text        not null,
    created_at      timestamptz not null
);
