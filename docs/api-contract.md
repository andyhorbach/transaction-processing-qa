# API Contract — Transaction Processing System

This contract defines the minimal API needed to exercise the risks in [`risk-analysis.md`](risk-analysis.md). Every endpoint maps to one or more risk IDs (see §8). The open decisions D-1…D-6 from the risk analysis, plus D-7 (refund eligibility, identified during contract design), are resolved in §2. This document is the authoritative source for expected behaviour; tests assert this contract.

**Supported transaction types in this version: `DEPOSIT`, `WITHDRAWAL`, `REFUND`, `FEE`.** See §7 for what is deferred.

## 1. Conventions

- **Auth:** every request carries `Authorization: Bearer <token>`. Tokens belong to pre-seeded synthetic users (defined in test data docs). Missing/invalid token → `401 UNAUTHORIZED`.
- **Monetary amounts are JSON strings**, not numbers — e.g. `"250.00"` — to keep binary floating point out of the transport layer entirely (R-11). Format: positive decimal, at most 2 fraction digits, minimum `"0.01"`.
- **Error envelope** (all non-2xx):

  ```json
  { "error": { "code": "INSUFFICIENT_FUNDS", "message": "human-readable, no internal details" } }
  ```

- **Status code semantics:** `400` malformed input · `401` unauthenticated · `404` resource absent *or not owned* · `409` state/idempotency conflict · `422` business-rule rejection.

## 2. Resolved decisions

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| D-1 | Cross-user access semantics | **`404 NOT_FOUND`** for any resource the caller does not own — identical body to a truly absent resource | No existence disclosure; one consistent rule for read and write (R-16, R-17) |
| D-2 | Supported currencies | **`AUD`, `USD`, `EUR`** | Three is enough to test mismatch and unsupported-code paths |
| D-3 | Monetary scale & over-precise input | **Scale = 2 for all supported currencies, API and DB. Input with >2 fraction digits → `400 VALIDATION_ERROR`** | Rejection over silent rounding: silent rounding is untestable intent |
| D-4 | Refund model | **Partial refunds allowed.** Sum of all non-`FAILED` refunds against one original ≤ original amount | Richer risk surface (sum cap, concurrent partials); pending refunds count toward the cap to close the concurrent over-refund hole (R-13, R-14) |
| D-5 | Idempotency-key reuse with different payload | **`409 IDEMPOTENCY_KEY_REUSE`**, no execution of either payload | Ambiguous intent must fail loudly (R-08) |
| D-6 | Concurrent duplicate requests | **Exactly one request executes.** Original finished → replay stored response with header `Idempotency-Replay: true`. Original still in flight → `409 DUPLICATE_REQUEST_IN_PROGRESS` (retryable) | Replay gives clients safety; the in-flight conflict is honest and testable (R-07, R-09) |
| D-7 | Refund eligibility | **Only `COMPLETED` transactions of debit types (`WITHDRAWAL`, `FEE`) are refundable, and only on the account the original transaction belongs to.** `DEPOSIT` and `REFUND` transactions are not refundable | A refund returns previously debited funds. Refunding a credit (`DEPOSIT`, `REFUND`) would allow refund-of-refund chains — a money-creation loop (R-13, R-14); reversing a deposit is a different operation (a withdrawal), not a refund |

## 3. Endpoints

### 3.1 `POST /accounts` — create account

Body: `{ "currency": "AUD" }` → `201` with `{ id, currency, balance: "0.00", status: "ACTIVE" }`. Unsupported currency → `400`.

*Exists to:* create isolated test fixtures per user. (R-10, R-16 fixtures)

### 3.2 `GET /accounts/{accountId}`

`200` with account incl. current `balance`. Not owned → `404`.

*Exists to:* balance assertions — the primary oracle for every money test. (R-01…R-05, R-16, R-18)

### 3.3 `GET /accounts/{accountId}/transactions`

`200` with the account's transactions, newest first. Not owned → `404`.

*Exists to:* independent recomputation of balance from history. (R-04, R-16, R-19)

### 3.4 `POST /accounts/{accountId}/transactions` — create transaction

Header: `Idempotency-Key: <string ≤ 64 chars>` — **required**, unique per user.

Body:

```json
{
  "type": "DEPOSIT | WITHDRAWAL | REFUND | FEE",
  "amount": "100.00",
  "currency": "AUD",
  "original_transaction_id": "…"
}
```

`original_transaction_id` is required for `REFUND` and must be absent for other types.

`201` with the transaction in status `PENDING`.

Creation-time (advisory) checks:

- `currency` ≠ account currency → `422 CURRENCY_MISMATCH`
- debit types (`WITHDRAWAL`, `FEE`) with amount > balance → `422 INSUFFICIENT_FUNDS`
- `REFUND`: original must be a `COMPLETED`, refundable-type transaction per D-7 belonging to **the same account as the request path (`accountId`)** → else `422 REFUND_NOT_ALLOWED`; amount over remaining refundable → `422 REFUND_EXCEEDS_ORIGINAL`
- Idempotency per D-5/D-6.

(R-01, R-07…R-14, R-17)

### 3.5 `GET /transactions/{transactionId}`

`200`; not owned → `404`. (R-16, R-18)

### 3.6 `POST /transactions/{transactionId}/transitions` — advance lifecycle

Body: `{ "to": "PROCESSING | COMPLETED | FAILED" }`

- Transition not in the valid matrix (`PENDING→PROCESSING`, `PROCESSING→COMPLETED`, `PROCESSING→FAILED`) → `409 INVALID_STATE_TRANSITION`, **no state or balance change**. This includes repeats: `COMPLETED→COMPLETED` is invalid, which is the guard for R-04.
- `to: COMPLETED` — the **authoritative, atomic** step: balance effect + status change happen as one unit or not at all. Authoritative re-checks at this moment are defined in §4.
- `to: FAILED` — no balance effect, ever.

(R-01…R-06, R-13…R-15, R-19, R-20)

## 4. Transaction semantics

- **Balance effects apply only at the `PROCESSING→COMPLETED` transition.** `PENDING`, `PROCESSING`, and `FAILED` transactions never affect a balance (R-02).
- Effect per type on `COMPLETED`: `DEPOSIT` +amount · `WITHDRAWAL` −amount · `FEE` −amount · `REFUND` +amount.
- **`FEE` is a plain account debit with no destination or beneficiary account in this synthetic system.** It exists to provide a second debit type (for refund-eligibility and mixed-history testing) without introducing another ledger model.
- **Refund scope:** a `REFUND` may only reference an original transaction on the same account it is created on. A same-user-but-different-account original is rejected with `422 REFUND_NOT_ALLOWED`, exactly like a foreign or absent one. Rationale: a refund returns funds to the account that was debited; without this rule a caller could refund one account's transaction into another account, which both distorts each account's ledger history and widens the attack surface of the refund path.
- Creation-time checks are advisory (fast client feedback); completion-time checks are authoritative and atomic. The window between them is a **deliberate test surface**: two withdrawals may both pass creation, but concurrent completion must let exactly one succeed (R-05).

### 4.1 Insufficient funds at completion

When a debit transaction in `PROCESSING` is transitioned to `COMPLETED` and the account balance is insufficient **at that moment**:

- the request returns `422 INSUFFICIENT_FUNDS`;
- the transaction **remains `PROCESSING`** — the failed completion attempt is not a state transition;
- **no balance change occurs**;
- the completion **may be retried later** and succeeds if the balance has become sufficient by then; alternatively the caller may transition the transaction to `FAILED`.

The same pattern applies to `REFUND` completion when the refund cap (D-4) would be exceeded at that moment: `422 REFUND_EXCEEDS_ORIGINAL`, status stays `PROCESSING`, no balance change, retry permitted.

## 5. Idempotency semantics

- Idempotency applies to **transaction creation only** (`POST /accounts/{accountId}/transactions` — the money-moving request). It is not a global mechanism: transitions are guarded by the state matrix instead, and the other endpoints are reads.
- Key scope: per user.
- **A key is bound only by successful creation (`201`).** A rejected creation (any 4xx) does not reserve the key: the same key may be retried after the request is corrected or the precondition is satisfied (e.g. after funding the account). While a request is in flight, the key is temporarily claimed (see the in-flight rule below).
- Once a key is bound to a created transaction:
  - same key + same payload → the original `201` response is replayed with header `Idempotency-Replay: true`; no new transaction is created;
  - same key + different payload → `409 IDEMPOTENCY_KEY_REUSE` (D-5).
- Same key + same payload while the original request is still in flight → `409 DUPLICATE_REQUEST_IN_PROGRESS` (D-6). A **differing** payload is reported as `409 IDEMPOTENCY_KEY_REUSE` even while the original is in flight — the payload mismatch is the more informative error and is detected first.

## 6. Error codes

| Code | HTTP | Trigger |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Malformed body, bad enum, >2 fraction digits, amount ≤ 0, missing Idempotency-Key |
| `UNAUTHORIZED` | 401 | Missing/invalid token |
| `NOT_FOUND` | 404 | Absent **or not-owned** resource (D-1) |
| `INVALID_STATE_TRANSITION` | 409 | Any (from,to) pair outside the matrix |
| `IDEMPOTENCY_KEY_REUSE` | 409 | Key reused with different payload |
| `DUPLICATE_REQUEST_IN_PROGRESS` | 409 | Concurrent duplicate, original in flight |
| `INSUFFICIENT_FUNDS` | 422 | Debit exceeds balance (at creation, or authoritatively at completion — §4.1) |
| `CURRENCY_MISMATCH` | 422 | Transaction currency ≠ account currency |
| `REFUND_NOT_ALLOWED` | 422 | Original not refundable per D-7 (wrong type, wrong status, or not on this account) |
| `REFUND_EXCEEDS_ORIGINAL` | 422 | Refund sum would exceed original (at creation, or authoritatively at completion — §4.1) |

## 7. Deferred to a later evolution

- **`TRANSFER`** is part of the domain model in the risk analysis but is **not supported in this version**. A correct transfer requires a ledger entry on *both* accounts (e.g. linked/mirror transactions) so that every account's balance remains equal to the sum of its own completed transactions; a single-sided design would itself violate R-19. It will be added as a deliberate evolution with its own contract revision and risks review.
- Account lifecycle (closing/freezing accounts) — `status` is always `ACTIVE` in this version.

## 8. Traceability: endpoint → risks

| Endpoint | Risks exercised |
|---|---|
| `POST /accounts` | fixtures for all |
| `GET /accounts/{id}` | R-01…R-05, R-16, R-18 |
| `GET /accounts/{id}/transactions` | R-04, R-16, R-19 |
| `POST /accounts/{id}/transactions` | R-01, R-07…R-14, R-17 |
| `GET /transactions/{id}` | R-16, R-18 |
| `POST /transactions/{id}/transitions` | R-02…R-06, R-13…R-15, R-19, R-20 |
