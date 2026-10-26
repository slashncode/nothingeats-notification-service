# nothingeats-notification-service

Outbound channels of [NothingEats](https://github.com/slashncode/nothingeats): mails that follow from domain events, and a server-sent event stream for live updates in the browser. Web push comes later.

## Mails

| Event (`identity.user.v1`) | Mail |
|---|---|
| `nothingeats.identity.user.registered.v1` | Confirm your address (link to `/verify?token=…` in the web app) |
| `nothingeats.identity.user.registration-attempted.v1` | Someone tried to sign up with your address |
| `nothingeats.identity.user.verified.v1` | Welcome |

| Event | Mail |
|---|---|
| `nothingeats.ordering.order.placed.v1` | Order confirmation: dishes, arrival time (Berlin), money not spent, calories not eaten, link to tracking |
| `nothingeats.delivery.delivery.status-changed.v1` with `DELIVERED` | "Your nothing has arrived" |

Order mails need the address, which identity events carry: the `recipients` table keeps address and language per user, kept current by every identity event with an address and erased by `user.deleted`. `cron replay-identity` rebuilds it from what `identity.user.v1` still holds (seven days), for accounts older than the table.

German and English, plain text plus HTML. SMTP to Mailpit locally (`http://mail.nothingeats.localhost`), Resend in production (planned).

## Live updates (`GET /api/notification/v1/stream`)

Server-sent events for the signed-in user (`Authorization: Bearer …`; the web app reads the stream with `fetch`, because `EventSource` cannot send headers).

- `event: ready` once the stream listens, then one event per update (`event: order.updated`, `data: {…}`), and a `: ping` comment every 15 s so proxies keep the connection open.
- Events: `order.updated` (`orderId`, `status`, `at`, `estimatedArrivalAt`, `courier`) from `ordering.order.v1` and `delivery.delivery.v1`; `courier.moved` (`orderId`, `latitude`, `longitude`, `status`, `at`) from `delivery.courier.v1`, consumed in a group of its own (`notification-service-live`) without an inbox: positions are frequent, ephemeral and harmless when repeated; `reward.earned` (XP, level, badges, stamp card, coupons, passed through as published) from `loyalty.reward.v1`, for the confetti.
- Event handlers publish through the `LiveUpdates` port to the user's Valkey channel `live:<userId>`; every pod subscribes to `live:*` and forwards to the streams it holds. So the pod that consumed a Kafka event does not need to hold the user's connection.
- Nothing is stored: an update nobody listens to is gone. A browser that reconnects loads the current state from the owning service.

## Guarantees

- The Kafka consumer (`notification-service` group) writes an inbox row and the delivery log in one transaction with the send: a mail goes out at least once and is not repeated after success.
- Unknown event types and fields are ignored (tolerant reader); failing events are retried, then parked in `notification-service.dlq`.
- Deliveries are logged with template and recipient domain, never the full address; the full address lives only in `recipients`. Every send is a PostHog `email_sent` event for the funnel to `email_verified`, but only when the event that caused it says the person had agreed to analytics (`analytics` in identity, ordering and delivery events; missing means no).

## Run

```bash
./gradlew build    # tests need Docker (Postgres, Kafka, Mailpit via Testcontainers)
```

In the k3d cluster: `just dev notification-service` from the hub repo.

## License

MIT
