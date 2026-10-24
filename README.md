# nothingeats-notification-service

Outbound channels of [NothingEats](https://github.com/slashncode/nothingeats). Phase 1: mails that follow from identity events. Later: web push and the server-sent event stream for live updates in the browser.

## Mails

| Event (`identity.user.v1`) | Mail |
|---|---|
| `nothingeats.identity.user.registered.v1` | Confirm your address (link to `/verify?token=…` in the web app) |
| `nothingeats.identity.user.registration-attempted.v1` | Someone tried to sign up with your address |
| `nothingeats.identity.user.verified.v1` | Welcome |

German and English, plain text plus HTML. SMTP to Mailpit locally (`http://mail.nothingeats.localhost`), Resend in production (planned).

## Guarantees

- The Kafka consumer (`notification-service` group) writes an inbox row and the delivery log in one transaction with the send: a mail goes out at least once and is not repeated after success.
- Unknown event types and fields are ignored (tolerant reader); failing events are retried, then parked in `notification-service.dlq`.
- Deliveries are logged with template and recipient domain, never the full address. Every send is a PostHog `email_sent` event for the funnel to `email_verified`.

## Run

```bash
./gradlew build    # tests need Docker (Postgres, Kafka, Mailpit via Testcontainers)
```

In the k3d cluster: `just dev notification-service` from the hub repo.

## License

MIT
