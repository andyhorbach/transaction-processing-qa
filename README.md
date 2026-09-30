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
- [ ] Minimal application under test
- [ ] API tests (functional + negative)
- [ ] Database validation
- [ ] Idempotency & concurrency tests
- [ ] E2E scenarios
- [ ] CI pipeline

## Repository structure

```text
transaction-processing-qa/
├── README.md
└── docs/
    ├── risk-analysis.md   ← start here
    └── api-contract.md    ← resolves the risk analysis's open decisions
```

## Author

**Andrii Horbach** — Senior QA Engineer (14+ years: fintech, healthcare, logistics, enterprise systems).
