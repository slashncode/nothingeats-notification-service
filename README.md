# nothingeats-notification-service

Outbound channels of [NothingEats](https://github.com/slashncode/nothingeats): mails that follow from domain events, a server-sent event stream for live updates in the browser, and web push for when the shop is closed.

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

## Web push (`/api/notification/v1/push`)

- `GET /key` (public): the VAPID public key for `pushManager.subscribe`; `404 PUSH_DISABLED` while `PUSH__PUBLIC_KEY`/`PUSH__PRIVATE_KEY` are not set (the shop then hides the switch).
- `PUT /subscriptions` with the browser's `PushSubscription.toJSON()` plus `language` (the language on screen); `GET /subscriptions` lists the user's devices by push service (also for the GDPR download); `DELETE /subscriptions?endpoint=` on switching off and on logout. `POST /test` pushes to every device of the user at once (shown even with the shop open) and answers how many the push services took, forgot (`gone`, then removed) or refused. A browser belongs to whoever subscribed last on it; a user keeps ten.
- `POST /subscriptions/rotation` (public) with `{ oldEndpoint, endpoint, keys }`: the service worker's `pushsubscriptionchange`, when the push service replaced a subscription. The worker has no session, so the old endpoint is the proof (a capability URL only that browser, its push service and this service know); the new subscription keeps the owner and language. Always `204`, known or not, so it tells a caller nothing.
- Endpoints must be a known push service over https (Google, Mozilla, Apple, Windows): the service sends requests to what browsers hand it, so anything else is refused (`422 PUSH_SUBSCRIPTION_INVALID`). Redirects are not followed.
- Pushes: `EN_ROUTE` and `DELIVERED` from `delivery.delivery.v1`, a new level or badges from a delivered order's `loyalty.reward.v1`, in the subscription's language. The service worker shows nothing while a tab of the shop is visible (the live stream does that). Encrypted (RFC 8291) and signed (VAPID) with `com.interaso:webpush`; a 404/410 from the push service removes the subscription (logged with the service's reason); any other failure is logged and never fails the event (that would resend its mail).
- `user.deleted` removes the account's subscriptions; logs name the push service's host only, never the endpoint (a capability URL).

## Guarantees

- The Kafka consumer (`notification-service` group) writes an inbox row and the delivery log in one transaction with the send: a mail goes out at least once and is not repeated after success.
- Unknown event types and fields are ignored (tolerant reader); failing events are retried, then parked in `notification-service.dlq`.
- Deliveries are logged with template and recipient domain, never the full address; the full address lives only in `recipients`. Every send is a PostHog `email_sent` event for the funnel to `email_verified`, but only when the event that caused it says the person had agreed to analytics (`analytics` in identity, ordering and delivery events; missing means no).

## Run

```bash
./gradlew build    # tests need Docker (Postgres, Kafka, Mailpit via Testcontainers); push tests decrypt what a fake push service receives
```

In the k3d cluster: `just dev notification-service` from the hub repo.

## License

MIT
