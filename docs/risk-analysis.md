# Risk Analysis — Transaction Processing System

This document is the foundation of the test strategy for this project. Every automated test in the repository traces back to a risk listed here, either directly or through the contract decision or section it verifies. Risks are prioritised by **impact** (what happens if it goes wrong) and **likelihood** (how easily a plausible implementation gets it wrong).

This document deliberately describes **what can go wrong, why it matters, and what must eventually be validated**. Where correct behaviour depends on a design choice that has not been made yet (exact error codes, monetary scale, refund model, …), the choice is not made here — it is listed in [§4 Open decisions](#4-open-decisions-deferred-to-the-api-contract) and will be fixed in the API contract. Tests will then validate the contract against these risks.

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
| R-21 | Lifecycle authorization | A party without processing authority (e.g. the account owner) drives the transaction lifecycle — such as completing their own deposit | Money creation | High | P1 — accepted, not mitigated (see §2.8) |
| R-22 | Input handling | Malformed, wrongly typed, or out-of-range input causes an uncontrolled server error or is silently coerced instead of being rejected | Reliability; unsafe coercion in the money path | High | P2 |
| R-23 | Refunds | Concurrent refunds against the same original block each other, so a valid refund can never complete | Refund cannot make progress | Medium | P2 |

## 2. Invariants and validation needs by area

### 2.1 Money integrity (R-01 … R-05)

The invariant: **an account balance must equal the sum of its completed transactions applied to the opening balance — at all times, under any concurrency.**

Why it matters: violations are direct money loss or phantom funds, and they compound silently — a ledger that is wrong by one transaction stays wrong forever unless independently recomputed.

What must hold and be validated:

- A `WITHDRAWAL` or `TRANSFER` whose amount exceeds the available balance must be rejected; the balance and transaction history remain unchanged.
- A transaction that ends in `FAILED` leaves the balance exactly as it was before the transaction started.
- A transaction that ends in `COMPLETED` changes the balance by exactly the transaction amount, exactly once — regardless of retries, crashes, or reprocessing attempts.
- Two concurrent withdrawals that together exceed the balance must not both complete; the balance never goes negative.

Validation approach: balance assertions before/after every state-changing operation; a dedicated concurrent-withdrawal scenario; DB-level recomputation of balance from transaction history as an independent oracle.

### 2.2 Transaction state transitions (R-06)

The defined lifecycle allows exactly these transitions:

```text
PENDING     → PROCESSING
PROCESSING  → COMPLETED
PROCESSING  → FAILED
```

The invariant: **everything else is invalid** — in particular anything *out of* a terminal state (`COMPLETED`, `FAILED`), skipping `PROCESSING`, or moving backwards. An invalid transition attempt must be rejected and must not touch the balance.

Why it matters: a transaction that leaves a terminal state can be processed again — which is risk R-04 wearing a different hat.

Validation approach: a transition matrix test — every (from, to) pair is attempted; the valid three succeed, all others are rejected. This is cheaper and more complete than testing transitions ad hoc.

### 2.3 Idempotency (R-07 … R-09)

The invariants:

- Retrying a request with the **same idempotency key and the same payload** must not create a second transaction or apply its effects twice.
- Reusing an existing key with a **different payload** must never silently execute either payload — the mismatch must be detected and surfaced explicitly. (How exactly it is surfaced is a contract decision — see §4.)
- **Concurrent** requests with the same key must result in exactly one processed transaction. (What the "losing" request receives is a contract decision — see §4.)

Why it matters: retries are not an edge case — clients, gateways, and networks retry as normal operation. A system that is only correct without retries is not correct.

Validation approach: sequential retry tests, payload-mismatch negative tests, and a parallel-duplicate test asserting exactly one transaction row per key.

### 2.4 Currency and monetary precision (R-10 … R-12)

The invariants:

- A transaction in a currency that does not match the account currency must not be booked as-is (this synthetic system does not perform FX conversion).
- Unsupported or malformed currency codes must be rejected on input.
- Monetary amounts must use exact decimal arithmetic end to end; binary floating point must not appear anywhere in the money path (API, application code, or DB column types).
- The precision/scale of amounts must be explicitly defined and identical across API and DB; input that exceeds it must be handled by an explicit, documented rule — never silently altered. (The scale itself and the handling rule are contract decisions — see §4.)

Why it matters: float-based money drifts by fractions of a cent per operation and by real money at volume; undefined precision produces API/DB disagreements that look like R-18 but are design defects.

Validation approach: boundary tests at the scale limit, classic float-trap amounts (e.g. `0.1 + 0.2`), and verification of DB column types.

### 2.5 Refunds (R-13 … R-15)

The invariants:

- A `REFUND` must reference an existing `COMPLETED` transaction of a refundable type.
- The total refunded against one transaction must never exceed the original transaction amount — regardless of whether refunds are full-only or partial (which model applies is a contract decision — see §4).
- Whether repeat refund attempts are possible at all follows from that same model; either way, over-refunding must be impossible, including under concurrent refund attempts.
- Refund completion must leave the refund transaction and the account balance consistent with each other.

Why it matters: refunds are the classic double-spend vector — they move money based on *history*, so any ambiguity about that history becomes money loss.

Validation approach: over-refund attempts, repeat-refund attempts (sequential and concurrent), refund-of-failed-transaction attempts — all derived from whichever refund model the contract fixes.

**Refund progress (R-23).** Preventing over-refunds is not sufficient on its own: the mechanism that prevents them must not also prevent *valid* refunds from completing. If concurrent refunds can together reserve more than the original amount, a cap check can reject every one of them, leaving each refund permanently blocked even though one of them would fit. The invariant: **every refund the system accepts must be able to complete**, and concurrent refund attempts must resolve to an outcome in which the refunds that fit can complete. (Where the cap is enforced is a contract decision — see §4.)

Validation approach: concurrent full-refund attempts against one original, asserting not only that no over-refund occurs but which attempts are accepted, and that each accepted refund then completes.

### 2.6 Authorization (R-16, R-17)

The invariants:

- Every account- and transaction-scoped operation must verify resource **ownership**, not just authentication.
- A user requesting another user's account or transaction must never receive the resource body, and the error behaviour must not leak information (e.g. by differing between "exists but forbidden" and "does not exist"). (The exact status-code convention is a contract decision — see §4.)
- A user must not be able to create, process, or refund transactions on another user's account.

Why it matters: this defect class (IDOR) is a data breach and, combined with write operations, money theft. It is also disproportionately common because authorization is easy to implement per-endpoint and forget per-resource.

Validation approach: a two-user fixture as a baseline of the test data design; every resource endpoint gets a cross-user negative test, read and write.

### 2.7 Database consistency (R-18 … R-20)

The invariants:

- After any API operation, the database must reflect exactly what the API reported: same status, same amounts, same balances.
- Transaction status change and balance change are one atomic unit — no observable state where one has happened and the other has not.
- A failure mid-operation must leave either the complete old state or the complete new state, never a mix.

Why it matters: API-vs-DB disagreement is corruption that no API-level test can see — which is precisely why the test strategy must not be API-only.

Validation approach: API tests paired with direct SQL assertions; a consistency check that recomputes each account balance from its transaction history and compares it with the stored balance — usable both as a test oracle and as a production-style data quality check.

### 2.8 Lifecycle authorization (R-21)

The risk: transaction lifecycle transitions — in particular completing a transaction, which is the step that moves money — are performed by a party that has no authority to process payments. In a real system, completion belongs to an internal processor or settlement step; a customer able to complete their own deposit can create money.

Why it matters: this is the most direct money-creation path in the domain, and ownership checks (§2.6) do not address it — the owner is authorized to *see* the transaction, not to *settle* it.

Status: **accepted, not mitigated.** The synthetic system deliberately lets the resource owner drive transitions, so that lifecycle, retry, and concurrency behaviour can be tested through the public API. This is a documented scope decision, not an oversight (see the API contract, D-11). Per §5, an accepted P1 risk is listed explicitly so that it reads as a decision rather than as an untested gap.

### 2.9 Input handling (R-22)

The invariants:

- Input that is malformed, of the wrong JSON type, or outside the permitted range must be rejected with a controlled client error — never with an unhandled server error, and never by reaching the database and failing there.
- Input must not be silently coerced into a different type or value; in particular, monetary amounts must not pass through binary floating point on their way in (this overlaps R-11 at the transport layer).
- Error responses must not expose internal details.

Why it matters: an input that produces a 5xx is both a reliability defect and an information-disclosure channel; silent coercion means the system acts on something the client did not literally send. (The exact accepted types, format, and bounds are contract decisions — see §4.)

Validation approach: wrong-type inputs (numbers, booleans, null where a string is required), malformed formats, values just inside and just outside each bound, and values beyond what the storage layer can hold — each asserted to return the contracted client error.

## 3. What is deliberately out of scope

To keep the system reviewable, the following are explicitly not modelled: FX conversion, multi-leg transfers, fee calculation logic, batch settlement, and regulatory reporting. Each would add real-world risks, but none is needed to demonstrate the QA reasoning above.

## 4. Open decisions deferred to the API contract

These choices affect how the risks above manifest and how the tests will assert. They are intentionally **not** decided in this document; the API contract will fix each one, and the tests will validate the contract.

| # | Decision | Related risks |
|---|---|---|
| D-1 | Error semantics for cross-user access (e.g. `403` vs `404`; must be consistent and non-leaking) | R-16, R-17 |
| D-2 | Supported currency set | R-10 |
| D-3 | Monetary scale per currency, and the rule for over-precise input (reject vs defined rounding) | R-11, R-12 |
| D-4 | Refund model: full-only vs partial refunds | R-13, R-14 |
| D-5 | Response to idempotency-key reuse with a different payload | R-08 |
| D-6 | Behaviour of the losing concurrent duplicate request (replay original response vs conflict error) | R-09 |

Further decisions were identified during contract design and later reviews; they are stated and resolved in the API contract rather than repeated here: D-7 (refund eligibility), D-8 (replay content — open), D-9 (where the refund cap is enforced — R-14, R-23), D-10 (amount wire format and bounds — R-11, R-12, R-22), D-11 (who drives the lifecycle — R-21), D-12 (idempotency fingerprint scope — R-08), and D-13 (in-flight key expiry and creation/binding atomicity — deferred with R-20).

## 5. How this document is used

- Each automated test identifies what it covers in its name: a risk ID, a contract decision, or a contract section — each of which traces back to risks in this document.
- Test review starts from this table: an uncovered P1 risk is a gap; a test that maps to no risk is a candidate for deletion. A risk explicitly marked *accepted, not mitigated* (currently R-21) is a documented scope decision, not a gap.
- When a defect is found, it is traced back here — either to a covered risk (test gap analysis) or to a missing risk (this document gets updated first, then the test is added).
- When an open decision from §4 is resolved in the API contract, the affected tests assert the contracted behaviour; this document keeps stating only the underlying risk.
