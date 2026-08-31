# Phase 2 — Cash ↔ Bank Transfer

**Date:** 2026-08-31
**Status:** Design approved in conversation. Awaiting written-spec review.
**Roadmap:** `2026-08-26-expense-tracker-roadmap.md`
**Depends on:** Phase 1 (`2026-08-26-transaction-edit-delete-design.md`), complete and merged.

## Problem

Moving money between cash and bank currently requires entering a fake expense
and a fake income of the same amount.

The roadmap describes this as inflating income and expense totals. That is
true but premature: the app shows no income or expense totals yet — the home
screen shows only total, cash, and bank balances, and reports are Phase 5. A
fake pair today actually produces **correct** balances.

What it breaks today is real, and it is three things:

**History lies about what happened.** Moving ₹5,000 from cash to bank renders
as a red `▼ DEBIT ₹5,000` and a green `▲ CREDIT ₹5,000`. Scanning history, that
reads as "I spent five thousand rupees and earned five thousand rupees." No
money entered or left the user's control.

**The two halves are not connected.** Phase 1 made every row editable and
deletable. Editing the amount on one half, or deleting one half, silently
leaves the other behind — cash and bank balances are then both wrong, with no
indication anything is broken and no way to notice except by arithmetic.

**It poisons two later phases.** Phase 5's spending reports must exclude these
rows, and Phase 3's loan movements need the same "this row is not real income
or expense" marker (see the settled loan decisions: loan movements post into
`transactions` tagged so expense reports can exclude them). Both need a marker
that does not exist.

## Goals

- Record a cash↔bank transfer as one atomic user action.
- Show it in history as one neutral row that reads as a movement, not as
  income or expense.
- Make it editable and deletable as a unit, consistent with Phase 1.
- Introduce the `kind` marker that Phase 3 and Phase 5 will both need.
- Keep every existing balance query correct and unchanged.
- Cover it all with tests that run without an emulator.

## Non-goals

- **No per-account ledger views.** "Show me only my bank transactions" is
  Phase 5 territory.
- **No conversion between kinds.** A transfer cannot be edited into a plain
  credit or debit, or vice versa. They are different record kinds; allowing
  the swap doubles every edit path for no real gain. To change one into the
  other, delete and re-add.
- **No overdraft protection.** Transferring more cash than is on hand is
  allowed, exactly as an oversized expense is allowed today. The balance goes
  red. Blocking it would be a new behaviour rule, not part of this phase.
- **No transfers to or from anywhere but cash and bank.** There are exactly
  two accounts.
- **No income/expense reporting.** Phase 2 lays the marker; Phase 5 uses it.

## Design

### 1. Data model

A transfer is stored as **two rows in `transactions` sharing a `transferId`
and an identical `dateTime`**: a debit leg on the source payment method and a
credit leg on the destination.

Two new columns:

```sql
kind       TEXT NOT NULL DEFAULT 'normal'   -- 'normal' | 'transfer'
transferId TEXT                             -- NULL for normal rows
```

**Why paired rows rather than a single row with a destination column.** Every
balance query already sums signed amounts filtered by `paymentMethod`. With
paired rows the debit leg lands naturally in the cash sum and the credit leg in
the bank sum, so `getBalance`, `getCashBalance`, and `getBankBalance` are all
**correct without modification**, and the two legs cancel in the total.
`recomputeBalanceCache` needs **no change whatsoever** — it operates per row,
and legs are just rows.

The rejected alternative — one row carrying `paymentMethod` plus
`toPaymentMethod` — would fork the sign convention. Cash balance would become
"signed sum of normal cash rows, minus transfers out of cash, plus transfers
into cash": a second, parallel definition of what money movement means. Phase 1
explicitly collapsed the signed-sum rule into a single `signedSum()` helper so
that the convention exists in exactly one place. Reintroducing a second
convention here would undo that.

A separate `transfers` table was also rejected: balances would have to UNION
two tables, and history would have to merge-sort across both while paginating
with a rowid tie-break. It also contradicts the already-settled Phase 3
decision that loan movements post **into** `transactions`.

The entire cost of this choice is confined to the history read path, described
in section 4.

**`kind` is not strictly required by Phase 2.** `transferId IS NOT NULL`
already identifies a transfer. It is included anyway because Phase 3's loan
rows need the same "not real income or expense" marker and will have no
`transferId` — so this is one migration instead of two, and future reports
filter on one predicate (`kind = 'normal'`) rather than a growing OR-chain.

### 2. Migration

`DATABASE_VERSION` goes from **2 to 3**.

`onCreate` gains both columns. `onUpgrade` gains an independent block:

```java
if (oldVersion < 2) { /* existing paymentMethod ALTER */ }
if (oldVersion < 3) {
    // ALTER TABLE transactions ADD COLUMN kind TEXT NOT NULL DEFAULT 'normal'
    // ALTER TABLE transactions ADD COLUMN transferId TEXT
}
```

The blocks are independent `if`s, not `else if`, so a **v1 device upgrades
straight to v3** by running both in order. `ADD COLUMN ... NOT NULL DEFAULT`
is legal in SQLite precisely because the default supplies a value for existing
rows.

**No data migration is needed.** Existing rows become `kind='normal'`,
`transferId=NULL`. Because `getTransactions` groups by
`COALESCE(transferId, id)` and every pre-existing row has a NULL `transferId`,
each old row forms its own group of one and renders exactly as before. Every
balance is unchanged. This mirrors Phase 1's property that an upgrade is
observationally invisible on existing data.

### 3. Write path

Three new `DatabaseHelper` methods, each wrapping its work in a single
`beginTransaction` / `endTransaction` and calling the existing
`recomputeBalanceCache(db)` before committing — the same shape Phase 1
established.

```java
boolean insertTransfer(double amount, String description,
                       String fromMethod, String toMethod);
boolean updateTransfer(String transferId, double amount, String description,
                       String fromMethod, String toMethod);
boolean deleteTransfer(String transferId);
```

**`insertTransfer`** generates one `transferId` and one `dateTime`, then
inserts:

1. the **debit leg first** — `type='debit'`, `paymentMethod=fromMethod`
2. the **credit leg second** — `type='credit'`, `paymentMethod=toMethod`

both with `kind='transfer'` and the shared `transferId`.

> **Load-bearing invariant.** The debit leg must be inserted before the credit
> leg. The collapsed history row shows the running balance *after the whole
> transfer*, which is read from the credit leg. That is only the post-transfer
> balance if the credit leg is the later of the two — see section 4. This is
> the same class of invariant as Phase 1's rowid ordering rule and gets the
> same comment treatment in the code.

**`updateTransfer`** updates each leg in place, matched by
`transferId AND type`. Reversing the direction is a *swap of `paymentMethod`
between the two legs*, not a delete-and-reinsert — so rowids, `dateTime`, and
therefore both the history position and the invariant above all survive an
edit. `dateTime` is deliberately preserved, matching `updateTransaction`.
Returns false unless both legs were updated.

**`deleteTransfer`** deletes every row with the given `transferId` and expects
exactly 2. There is no undo; the caller confirms first.

**Existing methods gain a guard.** `insertTransaction` writes
`kind='normal', transferId=NULL`. `updateTransaction` and `deleteTransaction`
must **refuse to act on a row whose `transferId` is non-null**, returning
false. Without this, any future caller holding a leg's id could orphan its
partner — the precise failure this phase exists to prevent.

### 4. Read path

This is the only place that pays for the paired-row model.

`getTransactions(offset, limit)` becomes a grouped query:

```sql
GROUP BY COALESCE(transferId, id)
ORDER BY MAX(dateTime) DESC, MAX(rowid) DESC
```

Both `ORDER BY` terms are aggregates, not bare columns — the same discipline
applied to the balance expression below.

A normal row is a group of one; a transfer is a group of two. Because
`amount`, `description`, and `dateTime` are written identically to both legs,
`MAX()` over them is exact rather than approximate.

Direction is recovered per group:

```sql
MAX(CASE WHEN type = 'debit'  THEN paymentMethod END) AS fromMethod
MAX(CASE WHEN type = 'credit' THEN paymentMethod END) AS toMethod
```

Both are NULL for a normal row.

**`Transaction` gains four fields** — `kind`, `transferId`, `fromMethod`, and
`toMethod` — populated from the grouped projection, with getters following the
existing style. `getTransaction(String id)` returns single rows, so it fills
`kind` and `transferId` from the row and leaves `fromMethod`/`toMethod` NULL;
only the grouped read path can know a direction.

**A row is treated as a transfer when both `fromMethod` and `toMethod` are
non-null**, not when `kind` says so. The two agree for every well-formed
record, and the stricter test is what makes an orphaned leg degrade to an
ordinary row instead of rendering `💵 → null` (section 6).

The running balance is:

```sql
CASE WHEN MAX(transferId) IS NULL
     THEN MAX(balanceAfter)                                   -- group of one
     ELSE MAX(CASE WHEN type = 'credit' THEN balanceAfter END) -- the later leg
END AS balanceAfter
```

Written this way deliberately. SQLite has a documented extension where a bare
column selected alongside `MAX()` takes its value from the matching row, which
would express "balanceAfter at max(rowid)" more briefly — but relying on a
dialect extension for a money figure is not worth the brevity. The `CASE` form
uses only standard aggregate semantics.

**`getTotalCount()` must count groups, not rows:**

```sql
SELECT COUNT(*) FROM (SELECT 1 FROM transactions GROUP BY COALESCE(transferId, id))
```

If it counts rows while `getTransactions` returns groups, `LIMIT`/`OFFSET`
pagination duplicates or skips rows across page boundaries — the exact bug
class Phase 1 fixed by making row order total, reintroduced through a
different door.

**`getTransfer(String transferId)`** is added for the edit form to prefill
from. `getTransaction(String id)` is unchanged and still returns single rows.

**`getBalance`, `getCashBalance`, `getBankBalance`, and
`recomputeBalanceCache` are not modified.** Tests pin this rather than leaving
it as an assumption.

### 5. UI

**Home screen.** A full-width `⇄  Transfer` button between the Income/Expense
pair and the History button, styled neutrally like History. Not a third button
in the Income/Expense row: that row is a visual pair, and filing a transfer
alongside income and expense is the exact confusion this phase removes.

**New `TransferActivity` + `activity_transfer.xml`**, rather than a third mode
inside `AddTransactionActivity`. That activity already carries create mode,
edit mode, and the type toggle; a third mode would make it the largest and
least testable screen in the app. Fields: amount, description, a direction
radio group (`Cash → Bank` / `Bank → Cash`), a primary button reading
`SAVE TRANSFER` or `UPDATE TRANSFER`, a Delete button in edit mode only, and
Cancel.

The screen is themed with the existing `COLOR_TEAL` constant — currently dead
code, flagged as unused in the Phase 1 follow-ups — giving transfers a neutral
identity distinct from credit-green and debit-red.

Amount validation reuses the rules `AddTransactionActivity` already applies.
Every `DatabaseHelper` call is wrapped in `try/catch (SQLiteException)`, as
Phase 1 established.

**`TransactionAdapter`.** A transfer row renders:

- `⇄ TRANSFER` in teal, on a dark teal background
- the amount with **no** `+` or `−` sign — neither is true
- `💵 → 🏦` (or the reverse) in place of the single payment-method icon
- the running balance as usual

**`TransactionHistoryActivity`.** A tap routes by kind: a transfer opens
`TransferActivity` keyed by `transferId`; anything else opens
`AddTransactionActivity` as it does today.

### 6. Edge cases

**Same-method transfer is structurally impossible.** The direction control is
a two-option radio group offering only the two cross-method directions, so
cash→cash cannot be expressed. No validation rule is needed.

**Overdrawing is permitted.** Consistent with the app's existing treatment of
an oversized expense; the affected balance renders red.

**An orphaned leg** — a transfer row whose partner is missing, reachable only
through a bug or a manual database edit — produces a group where `fromMethod`
or `toMethod` is NULL. The adapter degrades to rendering it as an ordinary
credit or debit row rather than crashing or showing `💵 → null`. One test
covers this.

**A zero-row database** and **a database of only transfers** both return
correct zeroed or netted balances; covered by tests.

### 7. Testing

New Robolectric tests in `DatabaseHelperTest`, which already runs real SQLite
without an emulator:

- A transfer leaves the total balance unchanged.
- A cash→bank transfer moves cash down and bank up by the amount.
- History collapses the two legs into exactly one row, carrying the right
  direction and amount.
- `getTotalCount()` counts a transfer as one.
- Paginating across a page boundary that falls on a transfer neither
  duplicates nor skips a row.
- The collapsed row's balance equals the total balance after the transfer.
- Editing a transfer's amount moves both legs.
- Flipping direction swaps both legs and reverses the balance effect.
- Deleting a transfer removes both legs and restores both balances.
- `updateTransaction` and `deleteTransaction` refuse a transfer leg.
- A v2 database with existing rows upgrades to v3 with every balance and every
  per-row `balanceAfter` unchanged.
- An orphaned leg does not crash the read path.

## Risks

**The debit-before-credit invariant is silent if broken.** Nothing enforces
insertion order at the schema level; a future refactor that reorders the two
inserts would leave every collapsed transfer row showing a running balance off
by the transfer amount, with all other figures correct. Mitigated by a
load-bearing comment at the insert site and by the test asserting the
collapsed row's balance — the same mitigation Phase 1 used for its rowid rule.

**Grouped pagination is the return of a fixed bug.** `getTransactions` and
`getTotalCount` must agree on what a unit is. They are changed together and
tested together for exactly that reason.

**`kind` is written but not yet read.** Nothing in Phase 2 filters on it; its
consumers are Phases 3 and 5. It is a deliberate, single-column bet on two
already-designed phases, not speculative generality — but it is a bet.

**Known deferred issues are untouched.** `DatabaseHelper` is still constructed
per Activity rather than application-scoped, and `recomputeBalanceCache` is
still O(n²) row visits per mutation. `TransferActivity` adds a fourth
construction site to the first problem and two rows per transfer to the
second. Neither is made materially worse, and both remain recorded follow-ups
rather than Phase 2 scope.

## Verification

Unit tests pass; `assembleDebug` builds; and a manual device pass confirming:
a transfer moves both balances and leaves the total alone, history shows one
neutral row, editing the amount moves both sides, flipping direction reverses
it, deleting restores both balances, existing pre-upgrade data reads
identically after installing over it, and the Income/Expense/History flows all
behave exactly as they did before.
