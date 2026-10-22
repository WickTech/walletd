# walletd

[![CI](https://github.com/WickTech/walletd/actions/workflows/ci.yml/badge.svg)](https://github.com/WickTech/walletd/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

A double-entry wallet and payments service. Every money movement is a journal entry whose postings sum to zero. The database itself enforces that, so a bug in the service can't create or destroy money.

> **Status: step 1 of 8.** The skeleton, ledger schema, database invariants and CI are done. The API endpoints come next (see [Roadmap](#roadmap)).

## Ledger invariants (enforced in Postgres)

| Invariant | How |
|---|---|
| Every journal entry balances (≥ 2 legs, sum = 0) | Deferred constraint trigger, checked at `COMMIT` |
| No mixed currencies inside an entry | Same trigger |
| User wallets never go negative | `CHECK (type = 'SYSTEM' OR balance_minor >= 0)` |
| History is append-only | `BEFORE UPDATE/DELETE` triggers on `postings` and `journal_entries` |
| No floating-point money | `bigint` minor units (paise) |

Each invariant has a test in [`LedgerSchemaTests`](src/test/java/com/wicktech/walletd/LedgerSchemaTests.java).

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
./mvnw verify
```

The tests start real PostgreSQL 17 binaries in-process ([zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres)), so Docker isn't needed. Flyway applies the same migrations used in production.

## Stack

Java 25 · Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator) · PostgreSQL 17 · Flyway · JUnit 5 + AssertJ · GitHub Actions

## Roadmap

1. ✅ Skeleton, Compose Postgres, Flyway V1 ledger schema, CI
2. Accounts + transfer endpoint (double-entry postings in one transaction)
3. Testcontainers integration tests; parallel-debit concurrency test
4. `Idempotency-Key` filter (replay → same response, different body → 422)
5. JWT auth, OpenAPI docs, RFC 7807 errors
6. Razorpay-style HMAC webhook; outbox poller
7. Nightly reconciliation job, Prometheus metrics, Dockerfile, deploy
8. Architecture diagram and curl walkthrough

## License

MIT
