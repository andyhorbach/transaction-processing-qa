# API Contract — Transaction Processing System

This contract defines the minimal API needed to exercise the risks in [`risk-analysis.md`](risk-analysis.md). Every endpoint maps to one or more risk IDs (see §8). The open decisions D-1…D-6 from the risk analysis, plus D-7 and D-9…D-12 (identified during contract design and review), are resolved in §2. D-8 remains open (§5) and D-13 is deferred (§7). This document is the authoritative source for expected behaviour; tests assert this contract — except for the clauses listed under *Implementation status* below.

**Supported transaction types in this version: `DEPOSIT`, `WITHDRAWAL`, `REFUND`, `FEE`.** See §7 for what is deferred.

> **Implementation status.** Two decisions in this revision are specified but **not yet implemented**, and the test suite does **not yet assert** them. Until they are, the application's observed behaviour differs from this contract as follows:
>
> - **D-9 (refund cap authoritative at creation):** concurrent refunds that together exceed the original can currently all be created; their completions are then all rejected with `422 REFUND_EXCEEDS_ORIGINAL` (risk R-23).
> - **D-10 (amount contract):** JSON numbers are currently accepted and coerced (`"amount": 10.5` → `201`); amounts above `1000000.00` are currently accepted; amounts beyond the database column range (more than 17 integer digits) currently return `500` instead of `400` (risk R-22).
>
> This note is removed when the implementation and tests for D-9 and D-10 land.

## 1. Conventions

- **Auth:** every request carries `Authorization: Bearer <token>`. Tokens belong to pre-seeded synthetic users (defined in test data docs). Missing/invalid token → `401 UNAUTHORIZED`.
- **Monetary amounts are JSON strings**, not numbers — e.g. `"250.00"` — to keep binary floating point out of the transport layer entirely (R-11). The full amount contract is D-10 (§2): string only, unsigned decimal, scale 2, from `0.01` to `1000000.00` inclusive per transaction; anything else → `400 VALIDATION_ERROR`.
- **Error envelope** (all non-2xx):

  ```json
  { "error": { "code": "INSUFFICIENT_FUNDS", "message": "human-readable, no internal details" } }
  ```

- **Status code semantics:** `400` malformed or out-of-range input · `401` unauthenticated · `404` resource absent *or not owned* · `409` state/idempotency conflict · `422` business-rule rejection that depends on current state.

## 2. Resolved decisions

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| D-1 | Cross-user access semantics | **`404 NOT_FOUND`** for any resource the caller does not own — identical body to a truly absent resource | No existence disclosure; one consistent rule for read and write (R-16, R-17) |
| D-2 | Supported currencies | **`AUD`, `USD`, `EUR`** | Three is enough to test mismatch and unsupported-code paths |
| D-3 | Monetary scale & over-precise input | **Scale = 2 for all supported currencies, API and DB. Input with >2 fraction digits → `400 VALIDATION_ERROR`** | Rejection over silent rounding: silent rounding is untestable intent |
| D-4 | Refund model | **Partial refunds allowed.** The sum of all non-`FAILED` refunds against one original must never exceed the original amount — enforced authoritatively at creation (D-9) | Richer risk surface (sum cap, partials, concurrency). Pending refunds count toward the cap, so the cap reserves refundable amount at creation rather than discovering an over-allocation at completion (R-13, R-14, R-23) |
| D-5 | Idempotency-key reuse with different payload | **`409 IDEMPOTENCY_KEY_REUSE`**, no execution of either payload | Ambiguous intent must fail loudly (R-08) |
| D-6 | Concurrent duplicate requests | **Exactly one request executes.** Original finished → replay stored response with header `Idempotency-Replay: true`. Original still in flight → `409 DUPLICATE_REQUEST_IN_PROGRESS` (retryable) | Replay gives clients safety; the in-flight conflict is honest and testable (R-07, R-09) |
| D-7 | Refund eligibility | **Only `COMPLETED` transactions of debit types (`WITHDRAWAL`, `FEE`) are refundable, and only on the account the original transaction belongs to.** `DEPOSIT` and `REFUND` transactions are not refundable | A refund returns previously debited funds. Refunding a credit (`DEPOSIT`, `REFUND`) would allow refund-of-refund chains — a money-creation loop (R-13, R-14); reversing a deposit is a different operation (a withdrawal), not a refund |
| D-9 | Where the refund cap is enforced | **Authoritatively at creation.** Refund creations against the same original are serialized; each counts all non-`FAILED` refunds already recorded against that original. Of two concurrent full refunds when only one fits, exactly one is created (`201`) and the other receives `422 REFUND_EXCEEDS_ORIGINAL`. The completion-time check (§4.1) remains as a second safeguard | Keeps D-4 true in the persisted state at all times, and guarantees every created refund can complete. The alternative (advisory creation) let concurrent refunds over-allocate the cap and block each other permanently (R-23). This is the one deliberate exception to "creation checks are advisory" (§4) |
| D-10 | Amount wire format and bounds | **JSON string only** (number, boolean, null, array, object → `400`). Unsigned decimal: integer part without leading zeros (`0` allowed alone), optionally `.` plus 1–2 digits; no sign, exponent, separators, or whitespace. Scale 2 (D-3). Range `0.01` … `1000000.00` inclusive, per transaction. Any violation → `400 VALIDATION_ERROR`, rejected before reaching the database. Valid amounts are returned normalized to scale 2 (e.g. `"10.5"` → `"10.50"`) | Numbers would reintroduce binary floating point at the transport layer (R-11). Bounds are static input constraints independent of account state, hence `400` like the existing minimum, not `422`. Rejecting before persistence means no client input can cause a database-level error (R-22) |
| D-11 | Who drives the transaction lifecycle | **The resource owner may call the transition endpoint; no processor role exists.** Documented as an intentional synthetic simplification (§3.6) | Makes lifecycle, retry, and concurrency risks directly testable. R-21 is consequently accepted, not mitigated |
| D-12 | Idempotency fingerprint scope | **The fingerprint covers the account in the request path plus `type`, normalized `amount`, `currency`, and `original_transaction_id`.** Reusing a key for another account of the same user is a different payload → `409 IDEMPOTENCY_KEY_REUSE` (D-5) | A key identifies one intended money movement on one account; silently replaying a transaction from another account would be wrong in either direction (R-08) |

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

`original_transaction_id` is required for `REFUND` and must be absent for other types. `amount` follows D-10.

`201` with the transaction in status `PENDING`.

Creation-time checks — advisory, except where marked authoritative:

- `currency` ≠ account currency → `422 CURRENCY_MISMATCH`
- debit types (`WITHDRAWAL`, `FEE`) with amount > balance → `422 INSUFFICIENT_FUNDS` (advisory; authoritative at completion, §4.1)
- `REFUND`: original must be a `COMPLETED`, refundable-type transaction per D-7 belonging to **the same account as the request path (`accountId`)** → else `422 REFUND_NOT_ALLOWED`
- `REFUND`: amount over the remaining refundable amount (original minus all non-`FAILED` refunds) → `422 REFUND_EXCEEDS_ORIGINAL` — **authoritative and serialized per original (D-9)**
- Idempotency per D-5/D-6/D-12.

(R-01, R-07…R-14, R-17, R-22, R-23)

### 3.5 `GET /transactions/{transactionId}`

`200`; not owned → `404`. (R-16, R-18)

### 3.6 `POST /transactions/{transactionId}/transitions` — advance lifecycle

Body: `{ "to": "PROCESSING | COMPLETED | FAILED" }`

- Transition not in the valid matrix (`PENDING→PROCESSING`, `PROCESSING→COMPLETED`, `PROCESSING→FAILED`) → `409 INVALID_STATE_TRANSITION`, **no state or balance change**. This includes repeats: `COMPLETED→COMPLETED` is invalid, which is the guard for R-04.
- `to: COMPLETED` — the **authoritative, atomic** step: balance effect + status change happen as one unit or not at all. Authoritative re-checks at this moment are defined in §4.
- `to: FAILED` — no balance effect, ever.

**Who may call this endpoint (D-11).** In this synthetic system the transition endpoint stands in for an internal payment processor, and any owner of the transaction's account may call it. This simplification exists so that lifecycle, retry, and concurrency behaviour can be driven and tested directly through the public API, without modelling a separate processing component. A consequence is that an owner can complete their own `DEPOSIT` — in a real system that would be money creation (R-21). Production authorization of lifecycle transitions — restricting them to a processor identity — is **outside the current scope**; R-21 is documented as accepted, not mitigated.

(R-01…R-06, R-13…R-15, R-19, R-20, R-21)

## 4. Transaction semantics

- **Balance effects apply only at the `PROCESSING→COMPLETED` transition.** `PENDING`, `PROCESSING`, and `FAILED` transactions never affect a balance (R-02).
- Effect per type on `COMPLETED`: `DEPOSIT` +amount · `WITHDRAWAL` −amount · `FEE` −amount · `REFUND` +amount.
- **`FEE` is a plain account debit with no destination or beneficiary account in this synthetic system.** It exists to provide a second debit type (for refund-eligibility and mixed-history testing) without introducing another ledger model.
- **Refund scope:** a `REFUND` may only reference an original transaction on the same account it is created on. A same-user-but-different-account original is rejected with `422 REFUND_NOT_ALLOWED`, exactly like a foreign or absent one. Rationale: a refund returns funds to the account that was debited; without this rule a caller could refund one account's transaction into another account, which both distorts each account's ledger history and widens the attack surface of the refund path.
- Creation-time checks are advisory (fast client feedback); completion-time checks are authoritative and atomic. The window between them is a **deliberate test surface**: two withdrawals may both pass creation, but concurrent completion must let exactly one succeed (R-05). **Exception — the refund cap (D-9):** it is authoritative at creation, because a refund's limit depends on refund history rather than on a balance that later credits can replenish; enforcing it only at completion lets concurrent refunds over-allocate the cap and block each other (R-23).

### 4.1 Insufficient funds at completion

When a debit transaction in `PROCESSING` is transitioned to `COMPLETED` and the account balance is insufficient **at that moment**:

- the request returns `422 INSUFFICIENT_FUNDS`;
- the transaction **remains `PROCESSING`** — the failed completion attempt is not a state transition;
- **no balance change occurs**;
- the completion **may be retried later** and succeeds if the balance has become sufficient by then; alternatively the caller may transition the transaction to `FAILED`.

`REFUND` completion re-checks the refund cap as a second safeguard (defence in depth). Under D-9 every created refund already fits the cap, so this check is not expected to fire in normal operation; if it does, the same pattern applies: `422 REFUND_EXCEEDS_ORIGINAL`, status stays `PROCESSING`, no balance change.

## 5. Idempotency semantics

- Idempotency applies to **transaction creation only** (`POST /accounts/{accountId}/transactions` — the money-moving request). It is not a global mechanism: transitions are guarded by the state matrix instead, and the other endpoints are reads.
- Key scope: per user.
- **Fingerprint (D-12):** a request's payload identity is the account in the request path plus `type`, `amount` normalized to scale 2, `currency`, and `original_transaction_id`. "Same payload" and "different payload" below are defined by this fingerprint — so `"10"` and `"10.00"` are the same payload, while the same body sent to a different account is a different payload.
- **A key is bound only by successful creation (`201`).** A rejected creation (any 4xx) does not reserve the key: the same key may be retried after the request is corrected or the precondition is satisfied (e.g. after funding the account). While a request is in flight, the key is temporarily claimed (see the in-flight rule below).
- Once a key is bound to a created transaction:
  - same key + same payload → the original `201` response is replayed with header `Idempotency-Replay: true`; no new transaction is created;
  - same key + different payload → `409 IDEMPOTENCY_KEY_REUSE` (D-5) — including the same body sent to another account (D-12).
- Same key + same payload while the original request is still in flight → `409 DUPLICATE_REQUEST_IN_PROGRESS` (D-6). A **differing** payload is reported as `409 IDEMPOTENCY_KEY_REUSE` even while the original is in flight — the payload mismatch is the more informative error and is detected first.

### Known ambiguity — replay content (D-8 candidate)

The replay rule above says "the original `201` response is replayed". The current implementation re-reads the created transaction and returns its **current** state: a creation retried after the transaction has already progressed (e.g. to `COMPLETED`) replays with the current status, not a snapshot of the original `PENDING` response. Both readings are defensible — a stored snapshot gives byte-stable replays; current-state replay never serves stale data. This is an open contract decision, not a defect: it will be resolved explicitly as D-8 before any test asserts either behaviour, and the implementation stays as-is until then.

## 6. Error codes

| Code | HTTP | Trigger |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Malformed body, bad enum, missing Idempotency-Key, or an `amount` violating D-10: non-string type, invalid format, >2 fraction digits, below `0.01`, or above `1000000.00` |
| `UNAUTHORIZED` | 401 | Missing/invalid token |
| `NOT_FOUND` | 404 | Absent **or not-owned** resource (D-1) |
| `INVALID_STATE_TRANSITION` | 409 | Any (from,to) pair outside the matrix |
| `IDEMPOTENCY_KEY_REUSE` | 409 | Key reused with a different payload, as defined by the fingerprint (D-5, D-12) |
| `DUPLICATE_REQUEST_IN_PROGRESS` | 409 | Concurrent duplicate, original in flight |
| `INSUFFICIENT_FUNDS` | 422 | Debit exceeds balance (at creation, or authoritatively at completion — §4.1) |
| `CURRENCY_MISMATCH` | 422 | Transaction currency ≠ account currency |
| `REFUND_NOT_ALLOWED` | 422 | Original not refundable per D-7 (wrong type, wrong status, or not on this account) |
| `REFUND_EXCEEDS_ORIGINAL` | 422 | Refund sum would exceed original — authoritatively at creation (D-9), re-checked at completion as a safeguard (§4.1) |

## 7. Deferred to a later evolution

- **`TRANSFER`** is part of the domain model in the risk analysis but is **not supported in this version**. A correct transfer requires a ledger entry on *both* accounts (e.g. linked/mirror transactions) so that every account's balance remains equal to the sum of its own completed transactions; a single-sided design would itself violate R-19. It will be added as a deliberate evolution with its own contract revision and risks review.
- Account lifecycle (closing/freezing accounts) — `status` is always `ACTIVE` in this version.
- **D-13 — in-flight key expiry and atomicity of creation with key binding.** Identified by code review, not demonstrated: an in-flight claim has no expiry, so a process crash between claiming and finishing would leave the key claimed permanently; and the transaction row and the key binding are committed in separate steps, so a failure between them could release the key after the row exists. Demonstrating either requires fault injection inside the operation, so both are deferred together with R-20.
- **Balance magnitude.** D-10 bounds each transaction, not the account balance. Reaching the database column limit for a balance would take on the order of 10^11 maximum-size credits; this is documented as a non-goal rather than guarded.
- **Production lifecycle authorization (R-21)** — see D-11 and §3.6.

## 8. Traceability: endpoint → risks

| Endpoint | Risks exercised |
|---|---|
| `POST /accounts` | fixtures for all |
| `GET /accounts/{id}` | R-01…R-05, R-16, R-18 |
| `GET /accounts/{id}/transactions` | R-04, R-16, R-19 |
| `POST /accounts/{id}/transactions` | R-01, R-07…R-14, R-17, R-22, R-23 |
| `GET /transactions/{id}` | R-16, R-18 |
| `POST /transactions/{id}/transitions` | R-02…R-06, R-13…R-15, R-19, R-20, R-21 (accepted, D-11) |
