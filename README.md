# walletd

[![CI](https://github.com/WickTech/walletd/actions/workflows/ci.yml/badge.svg)](https://github.com/WickTech/walletd/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

A double-entry wallet and payments service. Every money movement is a journal entry whose postings sum to zero. The database itself enforces that, so a bug in the service can't create or destroy money.

> **Status: step 6 of 8.** Ledger schema and invariants, the account / top-up / transfer API, concurrency tests, idempotent requests, JWT auth with OpenAPI docs, the signed payment webhook and the outbox relay are done (see [Roadmap](#roadmap)).

## Ledger invariants (enforced in Postgres)

| Invariant | How |
|---|---|
| Every journal entry balances (≥ 2 legs, sum = 0) | Deferred constraint trigger, checked at `COMMIT` |
| No mixed currencies inside an entry | Same trigger |
| User wallets never go negative | `CHECK (type = 'SYSTEM' OR balance_minor >= 0)` |
| History is append-only | `BEFORE UPDATE/DELETE` triggers on `postings` and `journal_entries` |
| No floating-point money | `bigint` minor units (paise) |

Each invariant has a test in [`LedgerSchemaTests`](src/test/java/com/wicktech/walletd/LedgerSchemaTests.java).

## API

Amounts are integer minor units (`10000` = ₹100.00). Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`, including `401` and `403`. Interactive docs: **`/docs`** (Swagger UI), spec at `/v3/api-docs`.

| Method | Path | Does |
|---|---|---|
| `POST` | `/accounts` | Open a wallet: `{"ownerRef": "asha", "currency": "INR"}` → 201 (409 if that owner already has one) |
| `GET` | `/accounts/{id}` | Balance and details |
| `GET` | `/accounts/{id}/postings?limit=50` | Ledger history, newest first |
| `POST` | `/accounts/{id}/top-ups` | Credit from the gateway account: `{"amountMinor": 10000}`. Needs `Idempotency-Key` |
| `POST` | `/transfers` | `{"fromAccountId", "toAccountId", "amountMinor", "reference"}` → 201 journal entry; 422 for insufficient funds, same account or currency mismatch. Needs `Idempotency-Key` |
| `GET` | `/journal-entries/{id}` | An entry with its postings |

## Authentication and who may do what

walletd is a resource server: every request except `/actuator/health`, the docs and the webhook needs `Authorization: Bearer <JWT>` (HS256, signed with `WALLETD_JWT_SECRET`, `iss` = `walletd`, roles in the `roles` claim). It stores no passwords: tokens come from your identity provider.

| Rule | Detail |
|---|---|
| A wallet belongs to the token's `sub` | `POST /accounts` opens one for you; naming another owner needs ADMIN (`403` otherwise) |
| Others' wallets don't exist for you | Reading, listing postings or spending from a wallet you don't own returns `404`, so ids can't be probed |
| Transfers start from your wallet | Any wallet can receive |
| Top-ups are ADMIN only | Otherwise anyone could mint money; in production the signed payment webhook does this |
| Idempotency keys are per caller | Two users can use the same key without colliding |
| Bad, expired, wrong-issuer or wrongly-signed token | `401` |

For local demos, `WALLETD_DEV_TOKENS=true` enables `POST /dev/token`, which mints a token for any subject. **It must stay off in production.** The app refuses to start without a secret of at least 32 characters.

## Idempotent requests

`POST /transfers` and `POST /accounts/{id}/top-ups` require an `Idempotency-Key` header (1–255 printable ASCII characters, e.g. a UUID). A client that times out can safely retry:

| Situation | Result |
|---|---|
| First request | Runs once; `201` |
| Same key, same body | The stored response again, plus `Idempotent-Replayed: true`. No second money movement |
| Same key, different body or endpoint | `422` |
| Same key sent in parallel | One runs; the others wait for it and get its response (tested with 20 parallel requests: one journal entry) |
| Request that failed (e.g. insufficient funds) | Not stored. After fixing the cause, the same key works |
| Header missing or malformed | `400` |

How it works: the key row is claimed, the ledger is updated and the response is stored **in one database transaction**, so a crash can't leave "paid but not recorded" or the reverse. A parallel request blocks on the key's unique index until the first one commits or rolls back.

```bash
export WALLETD_JWT_SECRET=$(openssl rand -hex 32) WALLETD_WEBHOOK_SECRET=$(openssl rand -hex 32)
WALLETD_DEV_TOKENS=true ./mvnw spring-boot:run &      # see "Run locally"

tok() { curl -s localhost:8080/dev/token -H 'content-type: application/json' -d "$1" | jq -r .token; }
OPS=$(tok '{"subject":"ops","admin":true}'); ASHA=$(tok '{"subject":"asha"}'); RAVI=$(tok '{"subject":"ravi"}')

open() { curl -s localhost:8080/accounts -H "Authorization: Bearer $1" -H 'content-type: application/json' -d '{"currency":"INR"}' | jq -r .id; }
A=$(open $ASHA); B=$(open $RAVI)

curl -s localhost:8080/accounts/$A/top-ups -H "Authorization: Bearer $OPS" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"amountMinor":10000}'
curl -s localhost:8080/transfers -H "Authorization: Bearer $ASHA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":2500}"
curl -s localhost:8080/accounts/$B -H "Authorization: Bearer $RAVI"      # balanceMinor: 2500
```

## Concurrency

Each transfer locks both accounts with `SELECT … FOR UPDATE` **in account-id order**, so:
- simultaneous debits on one wallet queue up instead of failing. In the test, 20 parallel ₹10 transfers from a ₹100 wallet give exactly 10 × `201` and 10 × `422`;
- transfers in opposite directions (A→B and B→A) can't deadlock;
- the `@Version` column and a short retry loop are a safety net for anything that slips past the locks.

An earlier version used optimistic locking alone. Under the same test, about half the requests gave up after retries even though funds remained.

## Payment webhook

`POST /webhooks/payments` is how money normally enters: the payment provider calls it when a payment is captured, and the wallet is credited **once per `paymentId`**, however often the provider retries. It is not bearer-authenticated; the body is signed instead.

- Headers: `X-Walletd-Timestamp: <epoch seconds>` and `X-Walletd-Signature: sha256=<hex>`, where the signature is `HMAC-SHA256(secret, "<timestamp>.<raw body>")` with `WALLETD_WEBHOOK_SECRET` (32+ characters, required).
- The signature is checked on the raw bytes **before** the JSON is parsed, with a constant-time comparison. Deliveries whose timestamp is more than 5 minutes off are rejected, so a captured request can't be replayed later. Any signature problem is a plain `401`.
- Body: `{"eventId","type":"payment.captured","paymentId","accountId","amountMinor","currency"}`. Other event types get `200 {"status":"ignored"}` so the provider stops retrying.
- Results: `200 credited`, or `200 duplicate` for a redelivery (same entry id back). The same `paymentId` with a different amount or wallet is refused with `422` and nothing is credited. Unknown wallet is `404`, currency mismatch `422`.

```bash
BODY='{"eventId":"evt_1","type":"payment.captured","paymentId":"pay_123","accountId":"'$A'","amountMinor":5000,"currency":"INR"}'
TS=$(date +%s)
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$WALLETD_WEBHOOK_SECRET" -hex | sed 's/^.* //')
curl -s localhost:8080/webhooks/payments -H 'content-type: application/json' \
  -H "X-Walletd-Timestamp: $TS" -H "X-Walletd-Signature: sha256=$SIG" -d "$BODY"
```

## Outbox: events that can't be lost

Every ledger change also writes an event row (`outbox` table) **in the same transaction**, so an event exists if and only if the money moved. A background relay (`OutboxRelay`, every 2 s) claims unpublished rows with `FOR UPDATE SKIP LOCKED`, hands them to an `OutboxPublisher` and marks them done. The default publisher only logs; define your own `OutboxPublisher` bean to send to Kafka or a queue.

- **At least once:** if the broker is down, the row stays and is retried in order; a crash between publish and commit re-sends. Consumers should de-duplicate on the event id (`OutboxMessage.id`).
- Several instances can run the relay at once without publishing the same row twice at the same time (tested with 4 parallel relays over 60 events).
- Turn the background jobs off with `WALLETD_SCHEDULING_ENABLED=false` (tests do this and call the relay directly).

## Schema

```
accounts ──< postings >── journal_entries
idempotency_keys   (scope, key) → stored response, for safe retries
outbox             events written in the ledger transaction, published later
```

Migrations: [`src/main/resources/db/migration`](src/main/resources/db/migration) (Flyway).

## Run locally

```bash
docker compose up -d                                  # Postgres 17 on :5432
export WALLETD_JWT_SECRET=$(openssl rand -hex 32)       # 32+ characters, required (verifies bearer tokens)
export WALLETD_WEBHOOK_SECRET=$(openssl rand -hex 32)   # 32+ characters, required (verifies payment webhooks)
WALLETD_DEV_TOKENS=true ./mvnw spring-boot:run        # local demos only: enables POST /dev/token
# health: http://localhost:8080/actuator/health   docs: http://localhost:8080/docs
```

## Test

```bash
./mvnw verify                 # real PostgreSQL 17 started in-process, no Docker needed
./mvnw verify -Pcontainer     # same tests on the official postgres:17 image (Testcontainers, needs Docker)
```

Both paths apply the same Flyway migrations as production. CI runs both. The suite covers:
- the database invariants (`LedgerSchemaTests`);
- the HTTP API, plus parallel-overspend and opposite-direction transfer tests (`LedgerApiTests`);
- a randomized load test (`LedgerConcurrencyTests`): 200 random transfers from 16 threads across 6 wallets, repeated with fresh seeds. Money must be conserved, no wallet negative, and every stored balance equal to its postings. A failure prints the seed.

## Stack

Java 25 · Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator, Security resource server) · springdoc OpenAPI · PostgreSQL 17 · Flyway · JUnit 5 + AssertJ · Testcontainers · GitHub Actions

## Roadmap

1. ✅ Skeleton, Compose Postgres, Flyway V1 ledger schema, CI
2. ✅ Accounts, top-ups and transfers (double-entry postings in one transaction, ordered row locks)
3. ✅ Testcontainers run in CI; randomized concurrency test
4. ✅ `Idempotency-Key` support (replay → same response, different body → 422)
5. ✅ JWT auth, ownership rules, OpenAPI docs, RFC 9457 errors
6. ✅ Signed payment webhook (HMAC + replay window); outbox relay
7. Nightly reconciliation job, Prometheus metrics, Dockerfile, deploy
8. Architecture diagram and curl walkthrough

## License

MIT
