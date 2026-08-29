# Phase 1 — Transaction Edit/Delete + Computed Balances — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every headline balance a computed sum rather than a stored snapshot, then let the user edit and delete any transaction from the history screen.

**Architecture:** `balanceAfter` stops being load-bearing. `getBalance()`, `getCashBalance()` and `getBankBalance()` become `SUM(CASE WHEN type='credit' THEN amount ELSE -amount END)` queries; `balanceAfter` demotes to a display-only cache rebuilt whole by one `UPDATE` inside the same SQLite transaction as every insert/update/delete. On top of that safe foundation, `AddTransactionActivity` gains an edit mode (reached by tapping a history row) with an editable type toggle and a Delete button.

**Tech Stack:** Java 11, Android SDK 35 (minSdk 21), raw SQLite via `SQLiteOpenHelper`, RecyclerView, JUnit 4 + Robolectric (new).

**Spec:** `docs/superpowers/specs/2026-08-26-transaction-edit-delete-design.md`

**Roadmap:** `docs/superpowers/specs/2026-08-26-expense-tracker-roadmap.md`

**Branch:** `feat/transaction-edit-delete` (already created off `main`)

## Global Constraints

- **No schema change.** `DATABASE_VERSION` stays at `2`. No columns added, removed, or renamed. No new migration branch in `onUpgrade`.
- **minSdk 21.** No SQLite window functions (`SUM() OVER`) — they need SQLite 3.25+, which minSdk 21 devices may not have.
- **Java 11 source/target compatibility.** No records, no switch expressions.
- **Robolectric is the only new dependency in this phase.** Do not add AssertJ, Truth, Mockito, `androidx.test:core`, or anything else. Use plain JUnit 4 assertions and `RuntimeEnvironment.getApplication()` for the `Context`.
- **No undo for delete.** Confirmation dialog only.
- **No bulk edit or bulk delete.**
- **Out of scope entirely:** transfers, loans, categories, search, budgets, CSV export. Those are Phases 2–6.
- **Dark palette values already in use — reuse verbatim, do not invent new ones:**
  - green `0xFF3FB950`, red `0xFFF85149`, green-dim `0xFF0D2B1A`, red-dim `0xFF2D0F0F`, teal `0xFF00D4AA`
  - screen background `#0D1117`, surface `#161B22`, input `#1C2128`, primary text `#E6EDF3`, muted text `#8B949E`, hint `#484F58`
- **Amount formatting stays `"₹ %.2f"` / `"+ ₹%.2f"` / `"- ₹%.2f"` with `Locale.getDefault()`.**
- **Type values are the strings `"credit"` and `"debit"`. Payment method values are `"cash"` and `"bank"`.** Never introduce enums or different casing in this phase.
- **Every DB mutation wraps its write plus the cache rebuild in `beginTransaction()` / `setTransactionSuccessful()` / `endTransaction()`.**
- **Never call `db.close()`.** `SQLiteOpenHelper` hands out one shared cached instance; closing it closes it for every other caller and breaks the atomic mutation-plus-recompute. Close `Cursor`s in `finally`, never the database.

---

## File Structure

**Modified:**

- `gradle/libs.versions.toml` — add the Robolectric version and library entry.
- `app/build.gradle.kts` — add `testImplementation(libs.robolectric)` and a `testOptions` block.
- `app/src/main/java/com/example/expensetracker/DatabaseHelper.java` — the bulk of the work. Computed balances, `recomputeBalanceCache`, `getTransaction`, `updateTransaction`, `deleteTransaction`, cursor-mapping extraction, lifecycle cleanup.
- `app/src/main/res/layout/activity_add_transaction.xml` — add a hidden type `RadioGroup` and a hidden Delete button, both revealed only in edit mode.
- `app/src/main/java/com/example/expensetracker/AddTransactionActivity.java` — dual create/edit mode.
- `app/src/main/java/com/example/expensetracker/TransactionAdapter.java` — row click callback and a `clear()` method.
- `app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java` — open edit on row tap, reload from the first page on resume.

**Created:**

- `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java` — the whole Robolectric suite for this phase.

**Untouched:**

- `MainActivity.java` — already refreshes in `onResume()` via `getBalance()` / `getCashBalance()` / `getBankBalance()`, all of which keep their signatures.
- `Transaction.java` — the model is already sufficient.
- `ExampleUnitTest.java` and `ExampleInstrumentedTest.java` — generated stubs, leave them alone.

---

## Task 1: Robolectric harness + computed total balance

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Create: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a runnable Robolectric test class that later tasks add cases to; the private constant `SQL_SIGNED_SUM` (a SQL fragment `String`) used by Tasks 2 and 3; `public double getBalance()`, now computed.

**Background the implementer needs:**

`DatabaseHelper` needs an Android `Context`, so its tests cannot be plain JVM tests. Robolectric runs real Android framework code on the JVM without an emulator. `RuntimeEnvironment.getApplication()` returns the `Context` without pulling in `androidx.test:core`, which keeps Robolectric the only new dependency.

The database file name is a fixed constant (`expense_tracker.db`), so each test must start from a known-empty database. `context.deleteDatabase("expense_tracker.db")` in `@Before` guarantees that.

On the very first test run Robolectric downloads an `android-all` jar from Maven Central. That needs network access and can take a minute. This is expected, not a failure. If the SDK 35 jar cannot be resolved, annotate the class with `@Config(sdk = 33)` (importing `org.robolectric.annotation.Config`) and note the deviation.

- [ ] **Step 1: Add the Robolectric dependency**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
robolectric = "4.14.1"
```

and to `[libraries]`:

```toml
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

In `app/build.gradle.kts`, inside the `android { }` block directly after the existing `compileOptions { ... }` block, add:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

and inside `dependencies { }`, directly under the existing `testImplementation(libs.junit)` line, add:

```kotlin
    testImplementation(libs.robolectric)
```

- [ ] **Step 2: Write the first tests**

Create `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`:

```java
package com.example.expensetracker;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DatabaseHelperTest {

    /** Money comparisons are on doubles; a fraction of a paisa of slack is plenty. */
    private static final double DELTA = 0.001;

    private DatabaseHelper db;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("expense_tracker.db");
        db = new DatabaseHelper(context);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void emptyDatabaseReportsZeroNotNull() {
        assertEquals(0.0, db.getBalance(), DELTA);
    }

    @Test
    public void balanceIsCreditsMinusDebits() {
        db.insertTransaction("credit", 1000.0, "Salary", "bank");
        db.insertTransaction("debit", 250.0, "Groceries", "cash");
        db.insertTransaction("credit", 50.0, "Refund", "cash");

        assertEquals(800.0, db.getBalance(), DELTA);
    }
}
```

- [ ] **Step 3: Run the suite to confirm the harness works**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 2 tests. Both pass against the *old* snapshot implementation, because nothing has been edited or deleted yet — the snapshot is not yet wrong, only fragile. What this step actually proves is that Robolectric runs at all. If it errors on the `android-all` download, see the Background note above.

- [ ] **Step 4: Replace the snapshot read with a computed sum**

In `DatabaseHelper.java`, add this constant just below the column-name constants (after `COL_PAYMENT`):

```java
    /** Signed sum of matching rows: credits add, debits subtract. COALESCE because SUM over zero rows is NULL. */
    private static final String SQL_SIGNED_SUM =
            "COALESCE(SUM(CASE WHEN " + COL_TYPE + " = 'credit' THEN " + COL_AMOUNT
                    + " ELSE -" + COL_AMOUNT + " END), 0)";
```

Replace the whole `getBalance()` method with:

```java
    /**
     * Returns the current total balance, computed from every row.
     * Never reads the balanceAfter cache — that column is display-only.
     */
    public double getBalance() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + SQL_SIGNED_SUM + " FROM " + TABLE, null);
        try {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 0;
        } finally {
            cursor.close();
        }
    }
```

Leave `getBalanceInternal(SQLiteDatabase)` in place for now — `insertTransaction` still calls it, and Task 3 removes it.

- [ ] **Step 5: Run the tests to verify they still pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "test: add Robolectric harness and compute total balance from rows"
```

---

## Task 2: Computed cash and bank balances

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java` (`getBalanceByPaymentMethod`)
- Modify: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `SQL_SIGNED_SUM` from Task 1.
- Produces: `getCashBalance()` and `getBankBalance()` keep their existing public signatures; only the private helper behind them changes.

**Background the implementer needs:**

`paymentMethod` arrived in `DATABASE_VERSION` 2 via `ALTER TABLE ... ADD COLUMN paymentMethod TEXT DEFAULT 'cash'`. SQLite backfills existing rows with that default, but `getTransactions()` already defends against a `null` here, so the balance query defends too: `COALESCE(paymentMethod, 'cash')`. Without it, a stray `NULL` row would count toward the total balance but toward neither cash nor bank, and "total equals cash plus bank" would silently stop holding.

- [ ] **Step 1: Write the regression tests**

Add to `DatabaseHelperTest`:

```java
    @Test
    public void cashAndBankBalancesStayIsolated() {
        db.insertTransaction("credit", 5000.0, "Salary", "bank");
        db.insertTransaction("debit", 300.0, "Chai", "cash");
        db.insertTransaction("credit", 1000.0, "Cash gift", "cash");

        assertEquals(5000.0, db.getBankBalance(), DELTA);
        assertEquals(700.0, db.getCashBalance(), DELTA);
    }

    @Test
    public void totalBalanceEqualsCashPlusBank() {
        db.insertTransaction("credit", 5000.0, "Salary", "bank");
        db.insertTransaction("debit", 1200.0, "Rent", "bank");
        db.insertTransaction("debit", 300.0, "Chai", "cash");
        db.insertTransaction("credit", 800.0, "Gift", "cash");

        assertEquals(db.getCashBalance() + db.getBankBalance(), db.getBalance(), DELTA);
        assertEquals(4300.0, db.getBalance(), DELTA);
    }

    @Test
    public void emptyDatabaseReportsZeroCashAndBank() {
        assertEquals(0.0, db.getCashBalance(), DELTA);
        assertEquals(0.0, db.getBankBalance(), DELTA);
    }
```

- [ ] **Step 2: Run the tests and record that they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 5 tests. These three pass under the old `GROUP BY` implementation too — they are the regression net proving the rewrite in Step 3 preserves behaviour.

- [ ] **Step 3: Rewrite `getBalanceByPaymentMethod`**

Replace the entire existing `getBalanceByPaymentMethod` method with:

```java
    private double getBalanceByPaymentMethod(String paymentMethod) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT " + SQL_SIGNED_SUM + " FROM " + TABLE
                        + " WHERE COALESCE(" + COL_PAYMENT + ", 'cash') = ?",
                new String[]{paymentMethod});
        try {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 0;
        } finally {
            cursor.close();
        }
    }
```

Note there is no `db.close()` — see Global Constraints.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "refactor: compute cash and bank balances with a signed sum"
```

---

## Task 3: Balance cache rebuild, atomic insert, and lifecycle cleanup

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Modify: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `SQL_SIGNED_SUM` from Task 1.
- Produces: `private void recomputeBalanceCache(SQLiteDatabase db)` — call it inside an open transaction after any insert/update/delete, before `setTransactionSuccessful()`. Tasks 5 and 6 depend on it. `getBalanceInternal` no longer exists after this task.

**Background the implementer needs:**

The rebuild is a single correlated `UPDATE` over the whole table: each row's cache becomes the signed sum of every row at or before its own `dateTime`. That is O(n²) in principle and milliseconds in practice for one person's transaction history on one phone. If it ever becomes noticeable, the optimisation is to restrict the `UPDATE` to rows at or after the mutated timestamp — do not do that speculatively now.

Two rows sharing an identical millisecond timestamp will both show the sum including both. Entries are hand-typed, so a collision is effectively impossible, and the consequence is cosmetic — headline balances are unaffected. This is an accepted limitation, not a bug to fix.

`insertTransaction` writes a placeholder `0` into `balanceAfter` because the column is `NOT NULL`; the recompute overwrites it microseconds later inside the same transaction, so the placeholder is never observable.

- [ ] **Step 1: Write the cache regression test**

Add to `DatabaseHelperTest`:

```java
    @Test
    public void insertRebuildsThePerRowBalanceCache() {
        db.insertTransaction("credit", 1000.0, "Salary", "cash");
        db.insertTransaction("debit", 400.0, "Groceries", "cash");
        db.insertTransaction("credit", 100.0, "Refund", "cash");

        // getTransactions is newest-first, so the running balances read 700, 600, 1000.
        java.util.List<Transaction> all = db.getTransactions(0, 50);
        assertEquals(3, all.size());
        assertEquals(700.0, all.get(0).getBalanceAfterTransaction(), DELTA);
        assertEquals(600.0, all.get(1).getBalanceAfterTransaction(), DELTA);
        assertEquals(1000.0, all.get(2).getBalanceAfterTransaction(), DELTA);
    }
```

- [ ] **Step 2: Run the tests and record that they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 6 tests. Passes under the old snapshot-chaining insert — again a regression net, so Step 3 must not break it.

- [ ] **Step 3: Add `recomputeBalanceCache` and rewrite `insertTransaction`**

Add this private method to `DatabaseHelper`, directly above `insertTransaction`:

```java
    /**
     * Rebuilds the display-only balanceAfter column for every row, in one statement.
     * Must be called inside an open database transaction, after the mutation.
     * Nothing depends on this column for correctness — headline balances are computed.
     */
    private void recomputeBalanceCache(SQLiteDatabase db) {
        db.execSQL(
                "UPDATE " + TABLE + " SET " + COL_BALANCE + " = ("
                        + "SELECT COALESCE(SUM(CASE WHEN t2." + COL_TYPE + " = 'credit'"
                        + " THEN t2." + COL_AMOUNT + " ELSE -t2." + COL_AMOUNT + " END), 0)"
                        + " FROM " + TABLE + " t2"
                        + " WHERE t2." + COL_DATE + " <= " + TABLE + "." + COL_DATE + ")");
    }
```

Replace the entire `insertTransaction` method with:

```java
    /**
     * Insert a new transaction, then rebuild the balanceAfter display cache.
     * @param paymentMethod "cash" or "bank"
     * @return true if insert was successful
     */
    public boolean insertTransaction(String type, double amount, String description, String paymentMethod) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(COL_ID,      UUID.randomUUID().toString());
            values.put(COL_TYPE,    type);
            values.put(COL_AMOUNT,  amount);
            values.put(COL_DESC,    description);
            values.put(COL_DATE,    System.currentTimeMillis());
            values.put(COL_BALANCE, 0);   // placeholder; rebuilt below in the same transaction
            values.put(COL_PAYMENT, paymentMethod);

            if (db.insert(TABLE, null, values) == -1) {
                return false;
            }
            recomputeBalanceCache(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }
```

- [ ] **Step 4: Delete `getBalanceInternal`**

It has no callers left. Remove the whole method from `DatabaseHelper.java`.

- [ ] **Step 5: Remove every `db.close()` and fix the `getTotalCount` leak**

In `getTransactions`, delete the `db.close();` line from its `finally` block, keeping `cursor.close();`.

Replace the entire `getTotalCount` method — its cursor and database are currently not closed in a `finally`, unlike the neighbouring methods, so a throwing query leaks both:

```java
    /**
     * Returns the total number of transactions stored.
     */
    public int getTotalCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }
```

Then confirm no `db.close()` call remains anywhere in the file:

```bash
grep -n "db.close()" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
```

Expected: no output.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "refactor: rebuild balance cache atomically and stop closing the shared db handle"
```

---

## Task 4: Read a single transaction by id

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Modify: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public Transaction getTransaction(String id)` — returns the row, or `null` when no row has that id. `AddTransactionActivity` (Task 9) calls this to prefill the edit form. Also produces `private Transaction cursorToTransaction(Cursor cursor)`, shared with `getTransactions`.

- [ ] **Step 1: Write the failing tests**

Add these imports to `DatabaseHelperTest` alongside the existing static import:

```java
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
```

Add the tests:

```java
    @Test
    public void getTransactionReturnsTheMatchingRow() {
        db.insertTransaction("debit", 249.50, "Book", "cash");
        String id = db.getTransactions(0, 50).get(0).getId();

        Transaction found = db.getTransaction(id);

        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("debit", found.getType());
        assertEquals(249.50, found.getAmount(), DELTA);
        assertEquals("Book", found.getDescription());
        assertEquals("cash", found.getPaymentMethod());
    }

    @Test
    public void getTransactionOnUnknownIdReturnsNull() {
        db.insertTransaction("credit", 100.0, "Something", "cash");

        assertNull(db.getTransaction("no-such-id"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: FAIL to compile — `cannot find symbol: method getTransaction(String)`.

- [ ] **Step 3: Extract the cursor mapping and add `getTransaction`**

Add these two methods to `DatabaseHelper`, just below `getTransactions`:

```java
    /** Maps the row at the cursor's current position. Assumes the SELECT * column set. */
    private Transaction cursorToTransaction(Cursor cursor) {
        String paymentMethod = cursor.getString(cursor.getColumnIndexOrThrow(COL_PAYMENT));
        if (paymentMethod == null) paymentMethod = "cash"; // Default for old records

        return new Transaction(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_DESC)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE)),
                paymentMethod
        );
    }

    /**
     * Returns the transaction with the given id, or null if there is no such row.
     */
    public Transaction getTransaction(String id) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE + " WHERE " + COL_ID + " = ?", new String[]{id});
        try {
            return cursor.moveToFirst() ? cursorToTransaction(cursor) : null;
        } finally {
            cursor.close();
        }
    }
```

Then replace the whole `while` loop body inside `getTransactions` so it uses the shared mapper:

```java
            while (cursor.moveToNext()) {
                list.add(cursorToTransaction(cursor));
            }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: add getTransaction lookup by id"
```

---

## Task 5: Update a transaction

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Modify: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `recomputeBalanceCache(SQLiteDatabase)` from Task 3, `getTransaction(String)` from Task 4.
- Produces: `public boolean updateTransaction(String id, String type, double amount, String description, String paymentMethod)` — returns `true` when a row with that id existed and was updated, `false` when no row matched. Task 9 calls it.

**Background the implementer needs:**

`dateTime` is deliberately **not** in the `ContentValues`, so a corrected transaction keeps its original position in history. `id` and `balanceAfter` are likewise left alone — `balanceAfter` is rebuilt for every row by the recompute.

- [ ] **Step 1: Write the failing tests**

Add these imports to `DatabaseHelperTest`:

```java
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
```

Add the tests:

```java
    @Test
    public void editingAmountMovesTheBalance() {
        db.insertTransaction("debit", 5000.0, "Typo, meant 500", "cash");
        String id = db.getTransactions(0, 50).get(0).getId();

        assertEquals(-5000.0, db.getBalance(), DELTA);

        assertTrue(db.updateTransaction(id, "debit", 500.0, "Groceries", "cash"));

        assertEquals(-500.0, db.getBalance(), DELTA);
        assertEquals("Groceries", db.getTransaction(id).getDescription());
    }

    @Test
    public void editingTypeFromCreditToDebitMovesTheBalance() {
        db.insertTransaction("credit", 750.0, "Tapped the wrong button", "cash");
        String id = db.getTransactions(0, 50).get(0).getId();

        assertEquals(750.0, db.getBalance(), DELTA);

        assertTrue(db.updateTransaction(id, "debit", 750.0, "Dinner", "cash"));

        assertEquals(-750.0, db.getBalance(), DELTA);
        assertEquals("debit", db.getTransaction(id).getType());
    }

    @Test
    public void editingPaymentMethodMovesMoneyBetweenCashAndBank() {
        db.insertTransaction("debit", 200.0, "Snacks", "cash");
        String id = db.getTransactions(0, 50).get(0).getId();

        assertEquals(-200.0, db.getCashBalance(), DELTA);
        assertEquals(0.0, db.getBankBalance(), DELTA);

        assertTrue(db.updateTransaction(id, "debit", 200.0, "Snacks", "bank"));

        assertEquals(0.0, db.getCashBalance(), DELTA);
        assertEquals(-200.0, db.getBankBalance(), DELTA);
        assertEquals(-200.0, db.getBalance(), DELTA);
    }

    @Test
    public void editingPreservesTheOriginalTimestamp() {
        db.insertTransaction("debit", 100.0, "Original", "cash");
        Transaction before = db.getTransactions(0, 50).get(0);

        assertTrue(db.updateTransaction(before.getId(), "credit", 999.0, "Changed", "bank"));

        assertEquals(before.getDateTime(), db.getTransaction(before.getId()).getDateTime());
    }

    @Test
    public void updatingAnUnknownIdReturnsFalse() {
        db.insertTransaction("credit", 100.0, "Something", "cash");

        assertFalse(db.updateTransaction("no-such-id", "debit", 5.0, "x", "cash"));
        assertEquals(100.0, db.getBalance(), DELTA);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: FAIL to compile — `cannot find symbol: method updateTransaction(...)`.

- [ ] **Step 3: Add `updateTransaction`**

Add to `DatabaseHelper`, below `insertTransaction`, under a new banner comment matching the file's existing section style:

```java
    // ─── UPDATE ──────────────────────────────────────────────────────────────

    /**
     * Updates an existing transaction in place, then rebuilds the balance cache.
     * dateTime is deliberately preserved so the row keeps its position in history.
     *
     * @return true if a row with this id existed and was updated
     */
    public boolean updateTransaction(String id, String type, double amount,
                                     String description, String paymentMethod) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(COL_TYPE,    type);
            values.put(COL_AMOUNT,  amount);
            values.put(COL_DESC,    description);
            values.put(COL_PAYMENT, paymentMethod);

            int rows = db.update(TABLE, values, COL_ID + " = ?", new String[]{id});
            if (rows == 0) {
                return false;
            }
            recomputeBalanceCache(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: add updateTransaction with atomic balance cache rebuild"
```

---

## Task 6: Delete a transaction

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Modify: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `recomputeBalanceCache(SQLiteDatabase)` from Task 3.
- Produces: `public boolean deleteTransaction(String id)` — `true` when a row was removed, `false` when no row matched. Task 9 calls it.

**Background the implementer needs:**

This is the case the whole balance rework exists for. Deleting a mid-history row used to leave every later `balanceAfter` snapshot wrong, and the headline balance with it. The mid-history test below is the one that would have caught the original bug — do not weaken it.

- [ ] **Step 1: Write the failing tests**

Add to `DatabaseHelperTest`:

```java
    @Test
    public void deletingAMidHistoryRowKeepsEveryBalanceCorrect() {
        db.insertTransaction("credit", 1000.0, "Salary", "cash");
        db.insertTransaction("debit", 400.0, "Mistake", "cash");
        db.insertTransaction("debit", 100.0, "Chai", "cash");

        assertEquals(500.0, db.getBalance(), DELTA);

        // getTransactions is newest-first, so index 1 is the middle row.
        String midId = db.getTransactions(0, 50).get(1).getId();
        assertTrue(db.deleteTransaction(midId));

        assertEquals(900.0, db.getBalance(), DELTA);
        assertEquals(900.0, db.getCashBalance(), DELTA);

        java.util.List<Transaction> all = db.getTransactions(0, 50);
        assertEquals(2, all.size());
        assertEquals(900.0, all.get(0).getBalanceAfterTransaction(), DELTA);
        assertEquals(1000.0, all.get(1).getBalanceAfterTransaction(), DELTA);
    }

    @Test
    public void deletingTheOnlyTransactionReturnsBalanceToZero() {
        db.insertTransaction("credit", 1234.0, "Only one", "bank");
        String id = db.getTransactions(0, 50).get(0).getId();

        assertTrue(db.deleteTransaction(id));

        assertEquals(0.0, db.getBalance(), DELTA);
        assertEquals(0.0, db.getBankBalance(), DELTA);
        assertEquals(0, db.getTotalCount());
    }

    @Test
    public void getTotalCountReflectsADeletion() {
        db.insertTransaction("credit", 10.0, "A", "cash");
        db.insertTransaction("credit", 20.0, "B", "cash");
        db.insertTransaction("credit", 30.0, "C", "cash");

        assertEquals(3, db.getTotalCount());

        db.deleteTransaction(db.getTransactions(0, 50).get(0).getId());

        assertEquals(2, db.getTotalCount());
    }

    @Test
    public void deletingAnUnknownIdReturnsFalse() {
        db.insertTransaction("credit", 100.0, "Something", "cash");

        assertFalse(db.deleteTransaction("no-such-id"));
        assertEquals(1, db.getTotalCount());
        assertEquals(100.0, db.getBalance(), DELTA);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: FAIL to compile — `cannot find symbol: method deleteTransaction(String)`.

- [ ] **Step 3: Add `deleteTransaction`**

Add to `DatabaseHelper`, below `updateTransaction`:

```java
    // ─── DELETE ──────────────────────────────────────────────────────────────

    /**
     * Permanently removes a transaction, then rebuilds the balance cache.
     * There is no undo — callers must confirm with the user first.
     *
     * @return true if a row with this id existed and was removed
     */
    public boolean deleteTransaction(String id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.delete(TABLE, COL_ID + " = ?", new String[]{id});
            if (rows == 0) {
                return false;
            }
            recomputeBalanceCache(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: add deleteTransaction with atomic balance cache rebuild"
```

---

## Task 7: Edit-mode views in the add-transaction layout

**Files:**
- Modify: `app/src/main/res/layout/activity_add_transaction.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: four new view ids for Task 8 to find — `R.id.tvTypeSectionLabel`, `R.id.rgType` (a `RadioGroup` containing `R.id.rbCredit` and `R.id.rbDebit`), and `R.id.btnDelete`. All start `android:visibility="gone"` and are revealed only in edit mode.

**Background the implementer needs:**

There is no new screen in this phase. The same layout serves both create and edit; edit mode just reveals two extra controls. The type toggle copies the `rgPaymentMethod` pattern already in this file verbatim — `android:button="@null"`, `@drawable/radio_button_selector` background, `@drawable/radio_button_text_color` text colour, weighted halves — so it looks native to the screen. Do not invent a new selector drawable.

- [ ] **Step 1: Add the type toggle**

In `app/src/main/res/layout/activity_add_transaction.xml`, insert this block immediately **after** the `TextView` with `android:id="@+id/tvTypeLabel"` (the type badge) and **before** the `TextView` whose text is `"Amount"`:

```xml
            <!-- ── Type (edit mode only) ─────────────────────────────────── -->
            <TextView
                android:id="@+id/tvTypeSectionLabel"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Type"
                android:textSize="12sp"
                android:textColor="#8B949E"
                android:textStyle="bold"
                android:fontFamily="monospace"
                android:visibility="gone"
                android:layout_marginBottom="6dp"/>

            <RadioGroup
                android:id="@+id/rgType"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:visibility="gone"
                android:layout_marginBottom="22dp">

                <RadioButton
                    android:id="@+id/rbCredit"
                    android:layout_width="0dp"
                    android:layout_height="54dp"
                    android:layout_weight="1"
                    android:text="▲  CREDIT"
                    android:textSize="14sp"
                    android:textStyle="bold"
                    android:textColor="@drawable/radio_button_text_color"
                    android:fontFamily="monospace"
                    android:background="@drawable/radio_button_selector"
                    android:button="@null"
                    android:gravity="center"
                    android:layout_marginEnd="6dp"/>

                <RadioButton
                    android:id="@+id/rbDebit"
                    android:layout_width="0dp"
                    android:layout_height="54dp"
                    android:layout_weight="1"
                    android:text="▼  DEBIT"
                    android:textSize="14sp"
                    android:textStyle="bold"
                    android:textColor="@drawable/radio_button_text_color"
                    android:fontFamily="monospace"
                    android:background="@drawable/radio_button_selector"
                    android:button="@null"
                    android:gravity="center"
                    android:layout_marginStart="6dp"/>
            </RadioGroup>
```

Neither radio button carries `android:checked` — Task 8 checks the right one from the loaded transaction.

- [ ] **Step 2: Add the Delete button**

In the same file, insert this block immediately **after** the `Button` with `android:id="@+id/btnCancel"`, as the last child of the inner `LinearLayout`:

```xml
            <!-- ── Delete Button (edit mode only) ────────────────────────── -->
            <Button
                android:id="@+id/btnDelete"
                android:layout_width="match_parent"
                android:layout_height="52dp"
                android:text="DELETE TRANSACTION"
                android:textSize="15sp"
                android:textStyle="bold"
                android:backgroundTint="#2D0F0F"
                android:textColor="#F85149"
                android:visibility="gone"
                android:layout_marginTop="10dp" />
```

- [ ] **Step 3: Verify the layout still compiles**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. Nothing visible changes yet — all three new views are `gone`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_add_transaction.xml
git commit -m "feat: add hidden type toggle and delete button to the transaction form"
```

---

## Task 8: Edit mode in AddTransactionActivity

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/AddTransactionActivity.java`

**Interfaces:**
- Consumes: `getTransaction(String)` (Task 4), `updateTransaction(...)` (Task 5), `deleteTransaction(String)` (Task 6), and the view ids from Task 7.
- Produces: the intent contract Task 9 uses — `AddTransactionActivity` accepts a `String` extra named `"transactionId"`. When absent, the screen behaves exactly as it does today (create mode). When present, it opens in edit mode. The `"type"` extra keeps its current meaning in create mode and is ignored in edit mode.

**Background the implementer needs:**

The colour theming currently lives inline in `onCreate`. Edit mode lets the user change the type, so that theming has to run again whenever the toggle changes — it moves into `applyTypeTheme()`, called from both places.

Mis-tapping Income vs Expense is a real mistake worth being able to fix, which is why type is editable here even though it is fixed at creation.

Delete needs a confirmation dialog naming the amount and description, because it is irreversible and there is no undo.

- [ ] **Step 1: Add the new imports and fields**

At the top of `AddTransactionActivity.java`, add these imports to the existing block:

```java
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import java.util.Locale;
```

Replace the field declaration block with:

```java
    private EditText       etAmount, etDescription;
    private RadioGroup     rgPaymentMethod, rgType;
    private RadioButton    rbCash, rbBank, rbCredit, rbDebit;
    private TextView       tvTitle, tvTypeLabel;
    private Button         btnDone, btnDelete;
    private String         transactionType;
    private String         editingId;       // null in create mode
    private Transaction    editingOriginal; // null in create mode
    private DatabaseHelper dbHelper;
```

- [ ] **Step 2: Extract the theming into `applyTypeTheme`**

Add this method to `AddTransactionActivity`:

```java
    /** Re-colours the screen for the current transactionType. Safe to call repeatedly. */
    private void applyTypeTheme() {
        if ("credit".equals(transactionType)) {
            tvTypeLabel.setText("▲  CREDIT — Income");
            tvTypeLabel.setTextColor(COLOR_GREEN);
            tvTypeLabel.setBackgroundColor(COLOR_GREEN_DIM);
            btnDone.setBackgroundTintList(ColorStateList.valueOf(COLOR_GREEN));
            btnDone.setTextColor(0xFF0D1117);
        } else {
            tvTypeLabel.setText("▼  DEBIT — Expense");
            tvTypeLabel.setTextColor(COLOR_RED);
            tvTypeLabel.setBackgroundColor(COLOR_RED_DIM);
            btnDone.setBackgroundTintList(ColorStateList.valueOf(COLOR_RED));
            btnDone.setTextColor(0xFFFFFFFF);
        }
    }
```

- [ ] **Step 3: Rewrite `onCreate` to branch on the extra**

Replace the entire body of `onCreate` with:

```java
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_transaction);

        dbHelper  = new DatabaseHelper(this);
        editingId = getIntent().getStringExtra("transactionId");

        etAmount        = findViewById(R.id.etAmount);
        etDescription   = findViewById(R.id.etDescription);
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod);
        rbCash          = findViewById(R.id.rbCash);
        rbBank          = findViewById(R.id.rbBank);
        rgType          = findViewById(R.id.rgType);
        rbCredit        = findViewById(R.id.rbCredit);
        rbDebit         = findViewById(R.id.rbDebit);
        tvTypeLabel     = findViewById(R.id.tvTypeLabel);
        tvTitle         = findViewById(R.id.tvTitle);
        btnDone         = findViewById(R.id.btnDone);
        btnDelete       = findViewById(R.id.btnDelete);
        Button btnCancel = findViewById(R.id.btnCancel);
        TextView tvTypeSectionLabel = findViewById(R.id.tvTypeSectionLabel);

        if (editingId != null) {
            editingOriginal = dbHelper.getTransaction(editingId);
        }

        if (editingOriginal == null) {
            // ── Create mode ──
            editingId       = null;
            transactionType = getIntent().getStringExtra("type");
            tvTitle.setText("credit".equals(transactionType) ? "DEPOSIT" : "WITHDRAW");
            btnDone.setText("SAVE TRANSACTION");
        } else {
            // ── Edit mode ──
            transactionType = editingOriginal.getType();
            tvTitle.setText("EDIT");
            btnDone.setText("UPDATE TRANSACTION");

            etAmount.setText(String.format(Locale.getDefault(), "%.2f", editingOriginal.getAmount()));
            etDescription.setText(editingOriginal.getDescription());
            if ("bank".equals(editingOriginal.getPaymentMethod())) {
                rbBank.setChecked(true);
            } else {
                rbCash.setChecked(true);
            }

            if ("credit".equals(transactionType)) {
                rbCredit.setChecked(true);
            } else {
                rbDebit.setChecked(true);
            }

            tvTypeSectionLabel.setVisibility(View.VISIBLE);
            rgType.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());

            rgType.setOnCheckedChangeListener((group, checkedId) -> {
                transactionType = (checkedId == R.id.rbCredit) ? "credit" : "debit";
                applyTypeTheme();
            });
        }

        applyTypeTheme();

        btnDone.setOnClickListener(v   -> saveTransaction());
        btnCancel.setOnClickListener(v -> finish());
        etAmount.requestFocus();
    }
```

Note the `editingOriginal == null` fallback: if the row was deleted from another screen between the tap and this `onCreate`, the screen degrades to create mode rather than crashing.

- [ ] **Step 4: Branch `saveTransaction` between insert and update**

In `saveTransaction()`, replace the block starting at `boolean ok = dbHelper.insertTransaction(...)` through the end of the method with:

```java
        boolean ok;
        String successMessage;
        if (editingId == null) {
            ok = dbHelper.insertTransaction(transactionType, amount, description, paymentMethod);
            successMessage = "✓ Transaction saved";
        } else {
            ok = dbHelper.updateTransaction(editingId, transactionType, amount, description, paymentMethod);
            successMessage = "✓ Transaction updated";
        }

        if (ok) {
            Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error saving. Try again.", Toast.LENGTH_SHORT).show();
        }
```

Everything above that point — the empty checks, the `Double.parseDouble` guard, the `amount <= 0` guard, the `paymentMethod` read — stays exactly as it is.

- [ ] **Step 5: Add the delete confirmation**

Add this method to `AddTransactionActivity`:

```java
    /** Deleting is irreversible and there is no undo, so name the transaction in the prompt. */
    private void confirmDelete() {
        if (editingOriginal == null) return;

        String summary = String.format(Locale.getDefault(), "₹ %.2f — %s",
                editingOriginal.getAmount(), editingOriginal.getDescription());

        new AlertDialog.Builder(this)
                .setTitle("Delete this transaction?")
                .setMessage(summary + "\n\nThis cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (dbHelper.deleteTransaction(editingId)) {
                        Toast.makeText(this, "✓ Transaction deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Error deleting. Try again.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. Edit mode is not reachable from the UI yet — Task 9 wires that up.

- [ ] **Step 7: Run the test suite to confirm nothing regressed**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 17 tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/AddTransactionActivity.java
git commit -m "feat: add edit and delete mode to AddTransactionActivity"
```

---

## Task 9: Open edit from history and refresh on return

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/TransactionAdapter.java`
- Modify: `app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java`

**Interfaces:**
- Consumes: the `"transactionId"` intent extra contract from Task 8.
- Produces: `TransactionAdapter.OnTransactionClickListener` (single method `void onTransactionClick(Transaction transaction)`), a second `TransactionAdapter` constructor taking `(List<Transaction>, OnTransactionClickListener)`, and `TransactionAdapter.clear()`.

**Background the implementer needs:**

`TransactionHistoryActivity` currently loads pages only in `onCreate`, accumulating them into `allLoaded` with a running `currentOffset`. After an edit or delete it would show stale rows, so the load moves to `onResume`, which resets `allLoaded`, `currentOffset`, and the adapter first.

Reloading **only the first page** after returning is deliberate: re-fetching every previously loaded page would be wasteful, and the edited row is almost always near the top. The user can page down again if needed.

This also sidesteps a subtler bug. `OFFSET`-based pagination over a table whose row count just shrank skips a row at each page boundary. Resetting the offset avoids it entirely.

Move the initial load out of `onCreate` — `onResume` always runs right after it, so leaving both would load page one twice.

`MainActivity` needs no change; it already refreshes balances in `onResume`.

- [ ] **Step 1: Add the click callback and `clear()` to the adapter**

In `TransactionAdapter.java`, add the listener interface and a nullable field. Replace the existing field block and constructor with:

```java
    /** Callback for taps on a row. */
    public interface OnTransactionClickListener {
        void onTransactionClick(Transaction transaction);
    }

    private final List<Transaction> transactions;
    private final OnTransactionClickListener clickListener;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("dd MMM\nhh:mm a", Locale.getDefault());

    public TransactionAdapter(List<Transaction> transactions) {
        this(transactions, null);
    }

    public TransactionAdapter(List<Transaction> transactions, OnTransactionClickListener clickListener) {
        this.transactions   = transactions;
        this.clickListener  = clickListener;
    }
```

At the end of `onBindViewHolder`, after the existing if/else that sets the colours, add:

```java
        if (clickListener != null) {
            h.itemView.setOnClickListener(v -> clickListener.onTransactionClick(t));
        }
```

Add this method next to the existing `addAll`:

```java
    /** Drops every row. Used when the list is reloaded from the first page. */
    public void clear() {
        int removed = transactions.size();
        transactions.clear();
        notifyItemRangeRemoved(0, removed);
    }
```

- [ ] **Step 2: Wire the tap and the reload in the history screen**

In `TransactionHistoryActivity.java`, add these imports:

```java
import android.content.Intent;
```

Replace the adapter construction line in `onCreate`:

```java
        adapter    = new TransactionAdapter(allLoaded, this::openForEdit);
```

Delete the `// Initial load` comment and the `loadNextPage();` call at the end of `onCreate` — `onResume` handles it.

Add these two methods:

```java
    @Override
    protected void onResume() {
        super.onResume();
        // A transaction may have been edited or deleted while we were away.
        reloadFromStart();
    }

    private void reloadFromStart() {
        adapter.clear();
        currentOffset = 0;
        loadNextPage();
    }

    private void openForEdit(Transaction transaction) {
        Intent intent = new Intent(this, AddTransactionActivity.class);
        intent.putExtra("transactionId", transaction.getId());
        startActivity(intent);
    }
```

`loadNextPage` itself is unchanged.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.expensetracker.DatabaseHelperTest"`

Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/TransactionAdapter.java app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java
git commit -m "feat: open a transaction for editing from history and reload on return"
```

---

## Task 10: Full verification

**Files:** none modified unless a check fails.

**Interfaces:**
- Consumes: everything above.
- Produces: a verified, releasable branch.

- [ ] **Step 1: Run the whole unit test suite, not just this class**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS. 17 tests in `DatabaseHelperTest` plus the generated `ExampleUnitTest`.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm no constraint was violated**

```bash
grep -n "DATABASE_VERSION" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
grep -n "db.close()" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
grep -n "getBalanceInternal" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
```

Expected: `DATABASE_VERSION` is still `2`; the other two greps produce no output.

- [ ] **Step 4: Manual check on a device or emulator**

Install and exercise the app. Record the result of each:

1. Add a credit and a debit. Headline, cash, and bank balances on the home screen are correct.
2. Open history. Tap a row. The form opens titled `EDIT`, prefilled with the right amount, description, payment method, and type; the primary button reads `UPDATE TRANSACTION`; a Delete button is visible.
3. Change the amount, save. History shows the new amount, the row keeps its position in the list, and home-screen balances follow.
4. Edit a transaction's type from credit to debit. The screen re-colours as the toggle changes, and after saving all three balances move by twice the amount.
5. Edit a transaction's payment method from cash to bank. Cash and bank balances both move; the total is unchanged.
6. Add three transactions, delete the **middle** one, confirm the dialog names the right amount and description. After deleting, the headline balance and every per-row balance in history are correct.
7. Delete every transaction. All three balances read `₹ 0.00` and the empty-state message appears.
8. Open the add screen from the home screen's Debit and Credit buttons. Both still behave exactly as before — no type toggle, no Delete button.

- [ ] **Step 5: Verify existing data survives**

The spec flags this risk explicitly: no migration runs, and the recompute rewrites `balanceAfter` from the same rows that produced it, so existing snapshots should be reproduced identically. On a device that already holds real data from before this branch, install the new build and confirm the home-screen balances and the per-row history figures are unchanged from what they read before the upgrade.

- [ ] **Step 6: Commit anything the checks turned up**

If Steps 1–5 were all clean, there is nothing to commit and Phase 1 is done. If a fix was needed, commit it with a `fix:` message describing what the verification caught.

---

## Notes for whoever executes this

- **Do not skip a "run the test and watch it fail" step.** In Tasks 1–3 several tests pass before the implementation changes; that is called out where it happens, and those tests are regression nets rather than TDD drivers. In Tasks 4, 5 and 6 the failure is a real compile error and must be observed.
- **`./gradlew` on Windows:** use `./gradlew` from Git Bash, or `.\gradlew.bat` from PowerShell.
- **git on this machine** needed `safe.directory` set for `E:/ExpenseTracker` because of a Windows ownership false-positive. If git refuses to operate, that is the fix.
- **Phase 2 is cash↔bank transfer.** Do not start it here, and do not add schema or model hooks "for later" — YAGNI.
