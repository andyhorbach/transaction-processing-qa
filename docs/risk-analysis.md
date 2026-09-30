# Risk Analysis — Transaction Processing System

This document is the foundation of the test strategy for this project. Every automated test in the repository traces back to a risk listed here. Risks are prioritised by **impact** (what happens if it goes wrong) and **likelihood** (how easily a plausible implementation gets it wrong).

Priority scale: **P1** — money loss or data breach; must be covered before anything else. **P2** — incorrect but recoverable behaviour. **P3** — quality/UX issues.

## 1. Risk inventory

| ID | Area | Risk | Impact | Likelihood | Priority |
|---|---|---|---|---|---|
| R-01 | Money integrity | Withdrawal exceeding available balance is accepted | Direct money loss | Medium | P1 |
| R-02 | Money integrity | Failed transaction still modifies the balance | Money loss / phantom funds | Medium | P1 |
| R-03 | Money integrity | Completed transaction modifies the balance incorrectly or not at all | Ledger corruption | Medium | P1 |
| R-04 | Money integrity | The same transaction modifies the balance twice (e.g. on reprocessing) | Double credit/debit | High | P1 |
| R-05 | Money integrity | Concurrent processing causes double spending | Money loss | High | P1 |
| R-06 | State transitions | Invalid lifecycle transition accepted (e.g. `COMPLETED → PROCESSING`) | Reprocessing, double effects | Medium | P1 |
| R-07 | Idempotency | Retry with the same idempotency key creates a second transaction | Duplicate money movement | High | P1 |
| R-08 | Idempotency | Same idempotency key reused with a *different* payload is silently accepted | Ambiguous intent executed | Medium | P2 |
| R-09 | Idempotency | Concurrent duplicate requests both get processed | Duplicate money movement | High | P1 |
| R-10 | Currency & precision | Transaction currency does not match account currency but is accepted | Wrong amounts booked | Medium | P2 |
| R-11 | Currency & precision | Monetary amounts calculated with binary floating point | Cent-level drift, accumulating errors | High | P1 |
| R-12 | Currency & precision | Precision/scale of amounts undefined or inconsistent between API and DB | Rounding disputes | Medium | P2 |
| R-13 | Refunds | Refund exceeds the original transaction amount | Money loss | Medium | P1 |
| R-14 | Refunds | Same transaction refunded twice | Money loss | High | P1 |
| R-15 | Refunds | Refund breaks balance consistency (refund booked, balance not updated, or vice versa) | Ledger corruption | Medium | P1 |
| R-16 | Authorization | User can read another user's account or transactions (IDOR) | Data breach | High | P1 |
| R-17 | Authorization | User can create/modify transactions on another user's account | Money theft | High | P1 |
| R-18 | DB consistency | API response state disagrees with database state | Undetectable corruption | Medium | P2 |
| R-19 | DB consistency | Transaction status and account balance are mutually inconsistent | Ledger corruption | Medium | P1 |
| R-20 | DB consistency | Partial update leaves the system in an invalid intermediate state | Corruption after failure | Medium | P1 |

## 2. Expected behaviour by area

### 2.1 Money integrity (R-01 … R-05)

The invariant: **an account balance must equal the sum of its completed transactions applied to the opening balance — at all times, under any concurrency.**

Expected behaviour:

- A `WITHDRAWAL` or `TRANSFER` whose amount exceeds the available balance is rejected with an explicit error; the balance and transaction history are unchanged (no `FAILED` side effects on balance either).
- A transaction that ends in `FAILED` leaves the balance exactly as it was before the transaction started.
- A transaction that ends in `COMPLETED` changes the balance by exactly the transaction amount, exactly once — regardless of retries, crashes, or reprocessing attempts.
- Two concurrent withdrawals that together exceed the balance must not both complete. One succeeds, one is rejected; the balance never goes negative.

Test implications: balance assertions before/after every state-changing call; a dedicated concurrent-withdrawal scenario; DB-level recomputation of balance from transaction history as an independent oracle.

### 2.2 Transaction state transitions (R-06)

Valid transitions only:

```text
PENDING     → PROCESSING
PROCESSING  → COMPLETED
PROCESSING  → FAILED
```

Everything else is invalid — in particular anything *out of* a terminal state (`COMPLETED`, `FAILED`), skipping `PROCESSING`, or moving backwards. An invalid transition attempt must be rejected with an explicit error and must not touch the balance.

Test implications: a transition matrix test — every (from, to) pair is attempted; the valid three succeed, all others are rejected. This is cheaper and more complete than testing transitions ad hoc.

### 2.3 Idempotency (R-07 … R-09)

The contract:

- Retrying a request with the **same idempotency key and the same payload** returns the original result and creates **no** second transaction.
- Reusing an existing key with a **different payload** is a client error — the system must detect the mismatch and reject the request explicitly (not silently return the old result, not execute the new payload).
- **Concurrent** requests with the same key must result in exactly one processed transaction; the loser either receives the winner's result or a well-defined conflict error.

Test implications: sequential retry tests, payload-mismatch negative tests, and a parallel-duplicate test asserting exactly one row in the transaction table per key.

### 2.4 Currency and monetary precision (R-10 … R-12)

- The transaction currency must match the account currency; mismatches are rejected (this synthetic system does not perform FX conversion).
- Unsupported currency codes are rejected on input validation.
- All monetary amounts use exact decimal arithmetic (e.g. `DECIMAL` in the DB, `BigDecimal`-style types in code) — never binary floating point.
- Precision is explicit: amounts have a defined scale (2 decimal places for the supported fiat currencies); inputs exceeding the scale are rejected, not silently rounded.

Test implications: boundary tests at the scale limit (e.g. `10.001`), classic float-trap amounts (`0.1 + 0.2`), and DB column type verification.

### 2.5 Refunds (R-13 … R-15)

- A `REFUND` must reference an existing `COMPLETED` transaction of a refundable type.
- The refunded amount must not exceed the original transaction amount. If partial refunds are supported, the *sum* of refunds against one transaction must not exceed the original amount; if not supported, the second refund attempt is rejected outright. The choice must be explicit in the API contract — "unspecified" is a defect.
- Refund completion must atomically update both the refund transaction and the account balance.

Test implications: over-refund attempts, double-refund attempts (sequential and concurrent), refund-of-failed-transaction attempts.

### 2.6 Authorization (R-16, R-17)

- Every account- and transaction-scoped endpoint must verify resource ownership, not just authentication.
- User A requesting user B's account or transaction by ID receives `404` or `403` (the choice must be consistent — `404` avoids resource-existence disclosure) and never the resource body.
- User A must not be able to create, process, or refund transactions on user B's account.

Test implications: a two-user fixture is a baseline requirement of the test data design; every resource endpoint gets a cross-user negative test. These tests are cheap and catch a disproportionately expensive class of defects (IDOR).

### 2.7 Database consistency (R-18 … R-20)

- After any API operation, the database must reflect exactly what the API reported: same status, same amounts, same balances.
- There must be no observable intermediate state in which a transaction is `COMPLETED` but the balance is not yet updated (or vice versa) — status change and balance change are one atomic unit.
- A failure mid-operation (simulated where possible) must leave either the complete old state or the complete new state — never a mix.

Test implications: API tests are paired with direct SQL assertions; a consistency check recomputes each account balance from its transaction history and compares with the stored balance — usable both as a test oracle and as a production-style data quality check.

## 3. What is deliberately out of scope

To keep the system reviewable, the following are explicitly not modelled: FX conversion, multi-leg transfers, fees calculation logic, batch settlement, and regulatory reporting. Each would add real-world risks, but none is needed to demonstrate the QA reasoning above.

## 4. How this document is used

- Every automated test names the risk ID(s) it covers.
- Test review starts from this table: an uncovered P1 risk is a gap; a test that maps to no risk is a candidate for deletion.
- When a defect is found, it is traced back here — either to a covered risk (test gap analysis) or to a missing risk (this document gets updated first, then the test is added).
