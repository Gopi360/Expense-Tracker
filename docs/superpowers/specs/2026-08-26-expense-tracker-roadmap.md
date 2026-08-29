# Expense Tracker — Feature Roadmap

**Date:** 2026-08-26
**Status:** Agreed. Phase 1 specced; Phases 2–6 not yet designed.

## Context

The app currently supports adding income/expense against a cash or bank
balance, and viewing a paginated history. Six further features were
identified. They are sequenced below rather than built together, because
several change the same schema and an earlier phase unblocks a later one.

## Ordering rationale

The driving constraint is that **`balanceAfter` is currently load-bearing**.
Each transaction stores a running-balance snapshot, and the headline balance
is read from the newest row. Deleting or editing a row in the middle silently
invalidates every snapshot after it. Edit/delete therefore cannot be built
until balances are computed live.

That same fix is easier on today's smaller, simpler `transactions` table than
after loans and transfers have added more rows and more row kinds. So the
foundation work comes first, and loans — the feature originally requested —
lands third.

## Phases

| # | Feature | Depends on | Schema change |
|---|---------|-----------|---------------|
| 1 | Edit / delete transactions + computed balances | — | None |
| 2 | Cash ↔ Bank transfer | 1 | Yes |
| 3 | Loans (lend / borrow ledgers) | 1, 2 | Yes |
| 4 | Search & filter history | 1 | None expected |
| 5 | Categories + visual dashboard | 1 | Yes |
| 6 | CSV backup & restore | 1–5 | None |

Each phase ships something usable on the phone, and each gets its own
design spec and implementation plan.

### Phase 1 — Edit / delete + computed balances

Specced in `2026-08-26-transaction-edit-delete-design.md`.

### Phase 2 — Cash ↔ Bank transfer

Moving money between cash and bank currently requires a fake debit plus a
fake credit, which inflates both income and expense totals. A transfer
should move the money without counting as either.

Expected shape: two paired `transactions` rows sharing a `transferId`, plus
a `kind` concept marking rows that are not real income or expense. Not yet
designed.

### Phase 3 — Loans (lend / borrow)

Casual friend-lending, **not** a formal loan product. Decisions already made
during brainstorming:

- **Lending reduces cash/bank.** The money physically left the wallet, so the
  balance must reflect that. It is recorded as a movement, never as an
  expense, so spending reports stay honest.
- **Partial repayments are supported.** A loan is a parent record with
  repayment children; outstanding is derived (`principal − SUM(payments)`),
  never stored, so it cannot drift.
- **No due dates, no visible IDs, no status workflow.** Deliberately casual.
- **Two separate ledgers** in the Loans section: money lent out, and money
  borrowed. The main transaction history still reflects the underlying
  cash/bank movement.
- Approach chosen: separate `loans` and `loan_payments` tables, with loan
  movements posting into `transactions` tagged so they can be excluded from
  expense reporting.
- People are identified by free-text name with autocomplete from prior
  entries, matched case-insensitively. No contacts table.

Not yet designed in full.

### Phase 4 — Search & filter history

Filter by date range, description text, and cash/bank.

Note: the original suggestion bundled search & filter with *budgets* and
*recurring transactions*. Only search & filter is scheduled here; the other
two are separate features that have not been committed to. **Confirm this
reading before designing Phase 4.**

### Phase 5 — Categories + visual dashboard

Categories are **not** defined upfront. The field is optional free text that
autocompletes from previously used values, so the taxonomy assembles itself
from real spending instead of being guessed on day one. Existing transactions
stay uncategorised.

Dashboard: spend-by-category donut, six-month in/out bars, cash-vs-bank split.

### Phase 6 — CSV backup & restore

Full round trip: export to CSV, and import that CSV on a new phone to restore
everything. Scheduled last on purpose — every earlier phase changes the
schema, and an export that cannot restore the newer fields is worse than no
export at all.

## Open questions

- Phase 4 scope: search & filter only, or were budgets/recurring also wanted?
- Whether `fix/build-config` (build fix, verified) should merge to `main`.
