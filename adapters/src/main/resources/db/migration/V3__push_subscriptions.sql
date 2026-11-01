-- Browsers' push subscriptions (Push API), one row per endpoint. The endpoint is a capability URL
-- at a push service: with the keys, it reaches that browser. Erased with the account
-- (user.deleted), on logout, and when the push service no longer knows it (404/410).
create table push_subscriptions
(
    endpoint   text primary key,
    user_id    text        not null,
    p256dh     text        not null,
    auth       text        not null,
    language   text        not null,
    created_at timestamptz not null
);

create index push_subscriptions_user on push_subscriptions (user_id, created_at desc);
