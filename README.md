# walletd

[![CI](https://github.com/WickTech/walletd/actions/workflows/ci.yml/badge.svg)](https://github.com/WickTech/walletd/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

A double-entry wallet and payments service. Every money movement is a journal entry whose postings sum to zero. The database itself enforces that, so a bug in the service can't create or destroy money.

> **Status: step 3 of 8.** The ledger schema and invariants, the account / top-up / transfer API, and the concurrency tests are done (see [Roadmap](#roadmap)).

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

Amounts are integer minor units (`10000` = ₹100.00). Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.

| Method | Path | Does |
|---|---|---|
| `POST` | `/accounts` | Open a wallet: `{"ownerRef": "asha", "currency": "INR"}` → 201 (409 if that owner already has one) |
| `GET` | `/accounts/{id}` | Balance and details |
| `GET` | `/accounts/{id}/postings?limit=50` | Ledger history, newest first |
| `POST` | `/accounts/{id}/top-ups` | Credit from the gateway account: `{"amountMinor": 10000}` |
| `POST` | `/transfers` | `{"fromAccountId", "toAccountId", "amountMinor", "reference"}` → 201 journal entry; 422 for insufficient funds, same account or currency mismatch |
| `GET` | `/journal-entries/{id}` | An entry with its postings |

```bash
A=$(curl -s localhost:8080/accounts -H 'content-type: application/json' -d '{"ownerRef":"asha","currency":"INR"}' | jq -r .id)
B=$(curl -s localhost:8080/accounts -H 'content-type: application/json' -d '{"ownerRef":"ravi","currency":"INR"}' | jq -r .id)
curl -s localhost:8080/accounts/$A/top-ups -H 'content-type: application/json' -d '{"amountMinor":10000}'
curl -s localhost:8080/transfers -H 'content-type: application/json' \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":2500}"
```

## Concurrency

Each transfer locks both accounts with `SELECT … FOR UPDATE` **in account-id order**, so:
- simultaneous debits on one wallet queue up instead of failing. In the test, 20 parallel ₹10 transfers from a ₹100 wallet give exactly 10 × `201` and 10 × `422`;
- transfers in opposite directions (A→B and B→A) can't deadlock;
- the `@Version` column and a short retry loop are a safety net for anything that slips past the locks.

An earlier version used optimistic locking alone. Under the same test, about half the requests gave up after retries even though funds remained.

## Schema

```
accounts ──< postings >── journal_entries
idempotency_keys   (scope, key) → stored response, for safe retries
outbox             events written in the ledger transaction, published later
```

Migrations: [`src/main/resources/db/migration`](src/main/resources/db/migration) (Flyway).

## Run locally

```bash
docker compose up -d          # Postgres 17 on :5432
./mvnw spring-boot:run        # http://localhost:8080/actuator/health
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

Java 25 · Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator) · PostgreSQL 17 · Flyway · JUnit 5 + AssertJ · Testcontainers · GitHub Actions

## Roadmap

1. ✅ Skeleton, Compose Postgres, Flyway V1 ledger schema, CI
2. ✅ Accounts, top-ups and transfers (double-entry postings in one transaction, ordered row locks)
3. ✅ Testcontainers run in CI; randomized concurrency test
4. `Idempotency-Key` filter (replay → same response, different body → 422)
5. JWT auth, OpenAPI docs, RFC 7807 errors
6. Razorpay-style HMAC webhook; outbox poller
7. Nightly reconciliation job, Prometheus metrics, Dockerfile, deploy
8. Architecture diagram and curl walkthrough

## License

MIT
