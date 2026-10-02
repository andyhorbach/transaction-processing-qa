# Transaction Processing QA

A synthetic transaction-processing system built specifically as a **QA portfolio project**. It demonstrates how a senior QA engineer approaches a money-movement system: identify the risks first, derive the tests from the risks, and make every test traceable back to a reason.

> **Note:** This is a synthetic system designed for this portfolio. It contains no code, data, requirements, or documentation from any employer or real product.

## Five-minute tour

For a reviewer short on time, this path shows the core of the project:

1. [`docs/risk-analysis.md`](docs/risk-analysis.md) §1 — what can go wrong with money and why each risk is ranked the way it is.
2. [`docs/api-contract.md`](docs/api-contract.md) §2 — the explicit decisions that resolve the risk document's open questions, each with rationale.
3. [`StateTransitionMatrixTest`](src/test/java/io/github/andyhorbach/txnqa/api/StateTransitionMatrixTest.java) and [`LedgerOracle`](src/test/java/io/github/andyhorbach/txnqa/api/LedgerOracle.java) — how the tests keep their expectations independent of the implementation they verify.

## Why this project exists

Most QA demo repositories show *tool usage* — a Selenium suite here, a Postman collection there. This project instead shows *test reasoning*:

1. Here is a system.
2. Here are its important risks.
3. Here is how I decided what to test.
4. Here is how I test it.
5. Here is how I detect and investigate failures.
6. Here is how I prevent regressions.

## System under test

A minimal transaction-processing service with three core entities, plus a separate idempotency record:

```text
User
 └── Account (id, user_id, currency, balance, status)
      └── Transaction (id, account_id, type, amount, currency,
                       status, original_transaction_id, created_at)

IdempotencyRecord (user_id, idempotency_key, request_hash,
                   in_flight, transaction_id, created_at)
```

`original_transaction_id` is set only for `REFUND` transactions. Idempotency keys are not stored on the transaction itself: they live in the separate `idempotency_record` table, scoped per user, which links each successfully used key to the transaction it created.

Transaction types: `DEPOSIT`, `WITHDRAWAL`, `REFUND`, `FEE` (`TRANSFER` is deliberately deferred — see api-contract.md §7 for why a cheap single-sided design would violate the ledger invariant).

Transaction lifecycle:

```text
PENDING
  ↓
PROCESSING
  ├──→ COMPLETED
  └──→ FAILED
```

The system is intentionally small. The interesting part is not the implementation — it is what can go wrong with money when the implementation is careless.

## Test suite

**73 executed tests/cases, all passing** (39 test methods; 4 of them parameterized, expanding to 38 cases). All currently implemented risk-mapped scenarios pass. A portfolio review found three defects outside the suite's earlier assertions: JSON-number amounts were accepted, over-size amounts returned `500`, and concurrent refunds could block each other. They were resolved against contract decisions D-9 and D-10, and the tests that now cover them were confirmed to fail before the fix. Passing tests are a statement about these scenarios, not a proof of absence of defects.

Each test's display name identifies what it covers: a risk ID (e.g. `R-04`), a contract decision (e.g. `D-6`), or a contract section (e.g. `Contract 3.4`); the end-to-end journeys name the risks they complement. The full risk inventory lives in [`docs/risk-analysis.md`](docs/risk-analysis.md), and the decisions and sections in [`docs/api-contract.md`](docs/api-contract.md).

| Suite | Risks | What it demonstrates |
|---|---|---|
| `MoneyIntegrityTest` | R-01…R-05 | Overdraft guards at creation and (authoritatively) at completion; exactly-once balance effects; true-parallel double-spend attempt |
| `StateTransitionMatrixTest` | R-06 | All 16 lifecycle (from → to) pairs as one parameterized test; rejected transitions provably change nothing |
| `IdempotencyTest` | R-07…R-09 | The complete key-state × payload decision table, incl. 5 parallel requests with one key |
| `ValidationTest` | R-10…R-12, R-22 | Currency rules, monetary precision boundaries (incl. the `0.10 + 0.20` float trap), DB column scale, the amount contract (D-10: string-only type, inclusive bounds, over-size input rejected before the database) |
| `RefundTest` | R-13…R-15, R-23 | Refund caps with pending refunds counted, the cap enforced at creation under 5 concurrent full refunds (D-9), eligibility rules (D-7) |
| `AuthorizationTest` | R-16, R-17 | Cross-tenant reads *and* writes rejected; foreign resources byte-for-byte indistinguishable from absent ones (D-1) |
| `DatabaseConsistencyTest` | R-18, R-19 | API/DB agreement per field; the independent ledger oracle |
| `EndToEndJourneyTest` | journey-level | Three multi-step journeys over cumulative state (below) |

Run it (no Docker needed — the suite starts the app against a real in-process PostgreSQL):

```bash
./mvnw test
```

### Test design principles

- **Independent oracle:** `LedgerOracle` recomputes each balance from the `COMPLETED` history per the *contract's* effect rules. The application never computes balances that way (it mutates incrementally), so the same bug cannot pass on both sides.
- **Expectations come from the contract, not the code:** the valid-transition set and all journey end balances are hardcoded from `api-contract.md`, never derived from production enums or logic.
- **Real concurrency:** double-spend, idempotency-race, and refund-race tests fire actual parallel requests released by a barrier.
- **Isolation by fixture design:** every test creates its own accounts; nothing depends on execution order.
- There are deliberately **no unit tests**: this project is a black-box API/integration QA exercise — the system is validated through its public contract and its database, the way a QA engineer meets a real service — not because unit testing is overlooked.

### End-to-end journeys

The three journeys in `EndToEndJourneyTest` are complementary coverage over the risk-mapped suites: they assert *cumulative* state across realistic multi-step lifecycles, which single-risk tests structurally cannot. They do not by themselves prove any risk.

1. **Customer lifecycle** — open → fund → spend (withdrawal + fee) → one failed attempt → partial refund; final balance, exact history count and order, oracle, and API/DB agreement all verified against hand-computed values.
2. **Unreliable client** — the same lifecycle where *every* step is retried: creations with the same idempotency key (must replay), transitions re-sent (answered by the state matrix), and a completion retried through an insufficient-funds rejection until funds arrive. Exactly one transaction per logical operation at the end.
3. **Concurrent tenants** — two users run their journeys simultaneously; isolation is probed mid-journey at every stage (foreign reads and writes must 404), and each tenant's ledger is verified independently.

## Project status

Implemented and verified:

- [x] Risk analysis (`docs/risk-analysis.md`)
- [x] API contract (`docs/api-contract.md`) — resolves decisions D-1…D-7 and D-9…D-12; D-8 remains open and D-13 is deferred.
- [x] Minimal application under test (Java 25, Spring Boot, PostgreSQL)
- [x] API tests (functional + negative), risk-traceable (RestAssured + JUnit 5)
- [x] Database validation (independent ledger oracle, R-18/R-19)
- [x] Idempotency & concurrency tests (parallel requests, full decision table)
- [x] E2E journeys (3 multi-step scenarios)

Remaining work and deliberate gaps:

- [ ] CI quality gate (GitHub Actions) — intentionally after the test pyramid, so it gates a real test architecture rather than decorating it
- [ ] R-20 (partial update on mid-operation failure) needs fault injection inside the DB transaction; out of scope for a black-box suite and documented as a gap, not an oversight
- [ ] Performance/load testing — not started

## Repository structure

```text
transaction-processing-qa/
├── README.md
├── docs/
│   ├── risk-analysis.md       ← start here: risks R-01…R-23, prioritised
│   └── api-contract.md        ← resolves the risk analysis's open decisions
├── compose.yaml               ← PostgreSQL for local runs
├── pom.xml
└── src/
    ├── main/                  ← the application under test
    │   ├── java/io/github/andyhorbach/txnqa/
    │   └── resources/db/migration/   ← schema + seeded test users (Flyway)
    └── test/java/io/github/andyhorbach/txnqa/api/
        ├── ApiTestBase.java           ← shared fixtures and helpers
        ├── LedgerOracle.java          ← independent DB oracle (R-18/R-19)
        ├── MoneyIntegrityTest.java        (R-01…R-05)
        ├── StateTransitionMatrixTest.java (R-06)
        ├── IdempotencyTest.java           (R-07…R-09)
        ├── ValidationTest.java            (R-10…R-12)
        ├── RefundTest.java                (R-13…R-15)
        ├── AuthorizationTest.java         (R-16, R-17)
        ├── DatabaseConsistencyTest.java   (R-18, R-19)
        └── EndToEndJourneyTest.java       (journeys)
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
