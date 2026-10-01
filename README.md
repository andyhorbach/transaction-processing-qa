# Transaction Processing QA

A synthetic transaction-processing system built specifically as a **QA portfolio project**. It demonstrates how a senior QA engineer approaches a money-movement system: identify the risks first, derive the tests from the risks, and make every test traceable back to a reason.

> **Note:** This is a synthetic system designed for this portfolio. It contains no code, data, requirements, or documentation from any employer or real product.

## Why this project exists

Most QA demo repositories show *tool usage* — a Selenium suite here, a Postman collection there. This project instead shows *test reasoning*:

1. Here is a system.
2. Here are its important risks.
3. Here is how I decided what to test.
4. Here is how I test it.
5. Here is how I detect and investigate failures.
6. Here is how I prevent regressions.

## System under test

A minimal transaction-processing service with three entities:

```text
User
 └── Account (id, user_id, currency, balance, status)
      └── Transaction (id, account_id, type, amount, currency,
                       status, idempotency_key, created_at)
```

Transaction types: `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REFUND`, `FEE`

Transaction lifecycle:

```text
PENDING
  ↓
PROCESSING
  ├──→ COMPLETED
  └──→ FAILED
```

The system is intentionally small. The interesting part is not the implementation — it is what can go wrong with money when the implementation is careless.

## QA concerns demonstrated

| Area | Examples |
|---|---|
| Money integrity | Overdraft rejection, exactly-once balance mutation, no double spending under concurrency |
| State transitions | Only valid lifecycle transitions accepted; terminal states are terminal |
| Idempotency | Safe retries, key-reuse with different payload, concurrent duplicate requests |
| Currency & precision | Currency validation, decimal precision policy, no floating-point money math |
| Refunds | Amount caps, double-refund prevention, balance consistency |
| Authorization | Account/transaction ownership isolation between users |
| Database consistency | API state vs. DB state agreement, no partial updates |

The full risk inventory with expected behaviour lives in [`docs/risk-analysis.md`](docs/risk-analysis.md) — it is the source document from which all tests in this repository are derived.

## Planned testing layers

| Layer | Purpose | Tooling (planned) |
|---|---|---|
| API contract & functional tests | Validate business rules at the API boundary, including negative paths | Java + RestAssured |
| Database validation | Verify DB state agrees with API responses; catch partial updates | SQL assertions alongside API tests |
| Idempotency & concurrency tests | Duplicate and parallel requests must not duplicate effects | Targeted concurrent test scenarios |
| A small set of E2E flows | Full user journeys across several operations | Kept deliberately few and meaningful |
| CI quality gate | Every change runs the suite; failures block merge | GitHub Actions |

## Project status

This repository is being built incrementally. Current state:

- [x] Risk analysis (`docs/risk-analysis.md`)
- [x] API contract (`docs/api-contract.md`)
- [x] Minimal application under test (Java 25, Spring Boot, PostgreSQL)
- [x] API tests (functional + negative), risk-traceable (RestAssured + JUnit 5)
- [x] Database validation (independent ledger oracle, R-18/R-19)
- [x] Idempotency & concurrency tests (parallel requests, full decision table)
- [ ] E2E scenarios
- [ ] CI pipeline

Run the suite (no Docker needed — tests start the app against an in-process PostgreSQL):

```bash
./mvnw test
```

## Repository structure

```text
transaction-processing-qa/
├── README.md
├── docs/
│   ├── risk-analysis.md   ← start here
│   └── api-contract.md    ← resolves the risk analysis's open decisions
├── compose.yaml           ← PostgreSQL for local runs
├── pom.xml
└── src/main/              ← the application under test
    ├── java/io/github/andyhorbach/txnqa/
    └── resources/db/migration/   ← schema + seeded test users (Flyway)
```

## Running the application under test

Prerequisites: JDK 25. PostgreSQL comes either from Docker or in-process.

With Docker:

```bash
docker compose up -d
./mvnw spring-boot:run
```

Without Docker (starts a real PostgreSQL in-process via Zonky embedded-postgres):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=embedded-pg
```

The API listens on `http://localhost:8080`. Two synthetic users are seeded
(static bearer tokens, deliberately simple — this system's QA focus is resource
ownership, not credential management):

| User | Token |
|---|---|
| alice | `qa-token-alice` |
| bob | `qa-token-bob` |

Example:

```bash
curl -s -X POST localhost:8080/accounts \
  -H "Authorization: Bearer qa-token-alice" \
  -H "Content-Type: application/json" \
  -d '{"currency": "AUD"}'
```

## Author

**Andrii Horbach** — Senior QA Engineer (14+ years: fintech, healthcare, logistics, enterprise systems).
