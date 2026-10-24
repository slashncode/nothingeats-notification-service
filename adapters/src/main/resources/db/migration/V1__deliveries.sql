-- Every mail sent, for support and later for matching provider webhooks. No full addresses.
create table deliveries
(
    id               uuid primary key,
    event_id         text        not null,
    template         text        not null,
    recipient_domain text        not null,
    sent_at          timestamptz not null
);

create index deliveries_event on deliveries (event_id);
