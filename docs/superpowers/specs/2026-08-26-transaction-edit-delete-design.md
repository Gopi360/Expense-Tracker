# Phase 1 — Edit / Delete Transactions + Computed Balances

**Date:** 2026-08-26
**Status:** Design approved in conversation. Awaiting written-spec review.
**Roadmap:** `2026-08-26-expense-tracker-roadmap.md`

## Problem

Two problems, one of which causes the other.

**There is no way to correct a mistake.** Enter ₹5000 instead of ₹500 and it
is permanent. This was hit repeatedly in real use.

**Edit and delete cannot be built safely today.** Every transaction stores a
`balanceAfter` snapshot, computed at insert time from the previous row's
snapshot. `getBalance()` reads the newest row's snapshot as the headline
balance. Deleting or editing a row in the middle of that chain leaves every
later snapshot wrong, and the headline balance wrong with it.

Meanwhile `getCashBalance()` and `getBankBalance()` compute live sums. The two
mechanisms agree today only because nothing can ever be edited or deleted —
precisely the property this phase removes.

## Goals

- Correct the balance model so no stored value is load-bearing.
- Let the user edit any field of a transaction, including its type.
- Let the user delete a transaction, with confirmation.
- Cover the balance logic with tests that run without an emulator.

## Non-goals

- No schema change. `DATABASE_VERSION` stays at 2, no columns added or
  removed, no migration. This phase changes query logic and UI only.
- No undo for delete. Confirmation dialog only.
- No bulk edit or bulk delete.
- No transfers, loans, categories, or search — those are later phases.

## Design

### 1. Balance model

Two things currently conflated get separated.

**Headline balances become computed, always.** Total balance:

```sql
SELECT COALESCE(SUM(CASE WHEN type = 'credit' THEN amount ELSE -amount END), 0)
FROM transactions
```

Cash and bank balances use the same expression with
`WHERE paymentMethod = ?`. This replaces both the snapshot read in
`getBalanceInternal()` and the `GROUP BY` form in
`getBalanceByPaymentMethod()`.

`COALESCE(..., 0)` is required: `SUM` over zero rows returns `NULL`, and an
empty database must report a balance of `0`, not null.

**`balanceAfter` demotes to a display-only cache.** It still powers the small
per-row "balance" figure in the history list, but nothing depends on it for
correctness. After every insert, edit, or delete, the column is rebuilt whole
in a single statement, inside the same database transaction as the mutation:

```sql
UPDATE transactions SET balanceAfter = (
  SELECT COALESCE(SUM(CASE WHEN t2.type = 'credit' THEN t2.amount ELSE -t2.amount END), 0)
  FROM transactions t2
  WHERE t2.dateTime <= transactions.dateTime)
```

> **SUPERSEDED (Amendment 3):** the `WHERE` clause above lost the tie-break
> that shipped. What's actually running adds a `rowid` correlation so ties on
> `dateTime` resolve deterministically:
> `WHERE t2.dateTime < transactions.dateTime OR (t2.dateTime = transactions.dateTime AND t2.rowid <= transactions.rowid)`.
> See "Amendments during execution" in
> `docs/superpowers/plans/2026-08-29-transaction-edit-delete.md`.

This is O(n²) in principle. With n being one person's transaction count on one
phone, it is milliseconds. If it ever becomes noticeable, the optimisation is
to restrict the update to rows at or after the mutated timestamp.

**Rejected alternative:** dropping `balanceAfter` and computing the running
balance per row at read time. SQLite window functions require 3.25+, and
`minSdk 21` reaches devices with older SQLite, so it would need a correlated
subquery on every read instead of on rare writes. Reads happen on every
scroll; writes are occasional manual entry.

**Accepted limitation:** two transactions sharing an identical millisecond
timestamp will show the same `balanceAfter` (the sum including both). Entries
are created by hand, so a collision is effectively impossible, and the
consequence is cosmetic only — headline balances are unaffected.

> **SUPERSEDED (Amendment 3):** this limitation was accepted on a false
> premise and is fixed, not accepted. Measured reality was roughly one
> collision per 17-test run across 7 trials, and the consequence was not
> cosmetic: tied rows got an identical running balance, and `getTransactions`'
> undefined tie order also corrupted `LIMIT`/`OFFSET` pagination. The shipped
> fix breaks ties with `rowid` (insertion order) in both
> `recomputeBalanceCache`'s correlation and `getTransactions`' `ORDER BY`. See
> "Amendments during execution" in
> `docs/superpowers/plans/2026-08-29-transaction-edit-delete.md`.

### 2. Edit / delete flow

No new screen. Tapping a row in `TransactionHistoryActivity` opens
`AddTransactionActivity` in edit mode, prefilled, reusing the existing form.

`AddTransactionActivity` gains an optional `transactionId` intent extra:

- **absent** — today's behaviour, unchanged. Create mode.
- **present** — edit mode. Title reads `EDIT`, primary button reads
  `UPDATE TRANSACTION`, and a Delete button appears.

Delete shows a confirmation dialog naming the amount and description, because
it is irreversible.

**Type is editable in edit mode.** Mis-tapping Income vs Expense is a mistake
worth being able to fix. The screen already re-themes itself by type in
`onCreate`; that logic moves into a method called both at creation and when
the toggle changes. The toggle follows the existing cash/bank `RadioGroup`
pattern already in `activity_add_transaction.xml`.

Editing preserves the original `dateTime`, so a corrected transaction keeps
its position in history.

**The history list must refresh on return.** `TransactionHistoryActivity`
currently loads pages only in `onCreate`, accumulating them into `allLoaded`
with a running `currentOffset`. After an edit or delete it would show stale
rows. On resume it therefore resets `allLoaded`, `currentOffset`, and the
adapter, then reloads from the first page.

Reloading only the first page after returning is deliberate: re-fetching every
previously loaded page would be wasteful, and the edited row is almost always
near the top. The user can page down again if needed.

This also avoids a subtler bug — `OFFSET`-based pagination over a table whose
row count just shrank will skip a row at each page boundary. Resetting the
offset sidesteps it entirely.

`MainActivity` already refreshes balances in `onResume`, so it needs no
change.

### 3. `DatabaseHelper` changes

New public methods:

- `getTransaction(String id)` → `Transaction` or `null`
- `updateTransaction(String id, String type, double amount, String description, String paymentMethod)` → `boolean`
- `deleteTransaction(String id)` → `boolean`

New private method:

- `recomputeBalanceCache(SQLiteDatabase db)` — runs the `UPDATE` above

Changed:

- `getBalance()` — computed sum instead of snapshot read
- `getBalanceByPaymentMethod()` — `CASE WHEN` form instead of `GROUP BY`
- `insertTransaction()` — calls `recomputeBalanceCache` rather than deriving
  the new balance from the previous snapshot
- `getBalanceInternal()` — removed; superseded

Every mutation wraps its write plus the recompute in
`beginTransaction()` / `setTransactionSuccessful()` / `endTransaction()` so a
failure cannot leave the cache half-rebuilt.

### 4. Targeted cleanups

Both are in code this phase already rewrites.

**Stop calling `db.close()` on every operation.** `SQLiteOpenHelper` returns a
shared cached instance; closing it closes it for every other caller. This is
survivable today, but delete-plus-recompute must run as one atomic
transaction, and closing the shared handle mid-flight is exactly where it
breaks. The helper manages its own lifecycle instead.

**Fix the leak in `getTotalCount()`.** Its cursor and database are not closed
in a `finally`, unlike the neighbouring methods, so a throwing query leaks
both.

### 5. Testing

The project has no real tests — only Android Studio's generated
`ExampleUnitTest` and `ExampleInstrumentedTest` stubs.

The balance logic is the risky part of this phase, so it gets covered.
**Robolectric** is added as a test dependency: `DatabaseHelper` needs an
Android `Context`, and Robolectric runs on the JVM without an emulator.
This is the only new dependency in this phase.

Cases to cover:

- Balance over mixed credits and debits
- Empty database reports `0`, not null
- Cash and bank balances stay isolated from each other
- Total balance equals cash plus bank
- Delete recomputes remaining balances correctly
- Delete the only transaction returns balance to `0`
- Edit changes amount and balances follow
- Edit changes type (credit → debit) and balances follow
- Edit changes payment method and both cash and bank balances follow
- `getTransaction` on an unknown id returns null
- `getTotalCount` reflects a deletion

## Risks

- **`insertTransaction` behaviour changes shape.** It no longer computes the
  new balance itself. Its return contract is unchanged, but the tests above
  must confirm inserts still produce correct balances.
- **Existing data.** No migration runs, and the recompute rewrites
  `balanceAfter` from the same rows that produced it, so existing snapshots
  should be reproduced identically. Worth verifying against a real device
  database before trusting it.

## Verification

- All tests pass.
- `./gradlew assembleDebug` succeeds.
- On a device: add, edit, and delete transactions; confirm headline, cash, and
  bank balances stay correct, and per-row balances stay consistent after a
  mid-history delete.
