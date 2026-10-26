-- Where order mails go, projected from identity events (address + language). Deleted with the
-- account (user.deleted); the only place this service keeps a full address.
create table recipients
(
    user_id    text primary key,
    email      text        not null,
    language   text        not null,
    updated_at timestamptz not null
);
