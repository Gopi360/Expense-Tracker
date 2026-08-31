# Phase 2 — Cash ↔ Bank Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user move money between cash and bank as one atomic action that shows in history as a single neutral row and counts as neither income nor expense.

**Architecture:** A transfer is two rows in the existing `transactions` table sharing a `transferId` and a `dateTime` — a debit leg on the source payment method, a credit leg on the destination. Every balance query therefore works unchanged, and `recomputeBalanceCache` is untouched. The entire cost lands on the history read path, which groups by `COALESCE(transferId, id)` so a transfer collapses to one row.

**Tech Stack:** Java 8, Android SDK, SQLite via `SQLiteOpenHelper`, JUnit 4 + Robolectric (real SQLite, no emulator needed).

**Spec:** `docs/superpowers/specs/2026-08-31-cash-bank-transfer-design.md`

## Global Constraints

- **`DATABASE_VERSION` becomes exactly `3`.** No other version value.
- **`kind` column:** `TEXT NOT NULL DEFAULT 'normal'`, values `'normal'` or `'transfer'` only.
- **`transferId` column:** `TEXT`, nullable, NULL for every non-transfer row.
- **The debit leg is always inserted before the credit leg.** Load-bearing — see Task 3. Breaking it makes every collapsed transfer row show a running balance off by the transfer amount while every other figure stays correct.
- **Do not modify `recomputeBalanceCache`, `getBalance`, `getCashBalance`, `getBankBalance`, or `signedSum`.** If a task seems to require it, stop and escalate — the design depends on these being untouched.
- **`dateTime` is preserved on every update path**, as Phase 1 established, so rows keep their history position.
- **Never use SQLite's bare-column-with-`MAX()` extension.** Every column in a `GROUP BY` projection must be wrapped in an aggregate.
- **All `DatabaseHelper` calls from an Activity are wrapped in `try/catch (SQLiteException)`** with `Log.e(TAG, ...)`, matching `AddTransactionActivity`.
- **Currency formatting is `String.format(Locale.getDefault(), "₹ %.2f", value)`.**
- **Commit style:** conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`).
- **Run tests with:** `./gradlew :app:testDebugUnitTest`
- **Build with:** `./gradlew :app:assembleDebug`

---

## File Structure

**Modified:**
- `app/src/main/java/com/example/expensetracker/DatabaseHelper.java` — schema, migration, transfer write path, grouped read path
- `app/src/main/java/com/example/expensetracker/Transaction.java` — four new fields
- `app/src/main/java/com/example/expensetracker/TransactionAdapter.java` — neutral transfer row rendering
- `app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java` — route taps by kind
- `app/src/main/java/com/example/expensetracker/MainActivity.java` — Transfer button wiring
- `app/src/main/res/layout/activity_main.xml` — Transfer button
- `app/src/main/AndroidManifest.xml` — register `TransferActivity`
- `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java` — new tests

**Created:**
- `app/src/main/java/com/example/expensetracker/TransferActivity.java` — the transfer form, create + edit modes
- `app/src/main/res/layout/activity_transfer.xml` — its layout

`TransferActivity` is a new file rather than a third mode inside `AddTransactionActivity`, which already carries create mode, edit mode, and the type toggle.

---

## Task 1: Schema and migration

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Test: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: package-private constants `DatabaseHelper.KIND_NORMAL = "normal"` and `DatabaseHelper.KIND_TRANSFER = "transfer"`; columns `kind` and `transferId` on the `transactions` table.

- [ ] **Step 1: Write the failing test**

Add to `DatabaseHelperTest.java`. It builds a v2 database by hand, then opens it with the new helper to force `onUpgrade`.

Add these imports at the top of the file if not already present:

```java
import android.database.sqlite.SQLiteDatabase;
```

```java
    @Test
    public void upgradingFromV2PreservesEveryBalance() {
        Context context = RuntimeEnvironment.getApplication();
        db.close();
        context.deleteDatabase("expense_tracker.db");

        // The schema exactly as it shipped at DATABASE_VERSION 2.
        SQLiteDatabase raw = context.openOrCreateDatabase(
                "expense_tracker.db", Context.MODE_PRIVATE, null);
        raw.execSQL("CREATE TABLE transactions ("
                + "id TEXT PRIMARY KEY, type TEXT NOT NULL, amount REAL NOT NULL, "
                + "description TEXT, dateTime INTEGER NOT NULL, balanceAfter REAL NOT NULL, "
                + "paymentMethod TEXT DEFAULT 'cash')");
        raw.execSQL("INSERT INTO transactions VALUES "
                + "('a','credit',1000.0,'Salary',1000,1000.0,'bank')");
        raw.execSQL("INSERT INTO transactions VALUES "
                + "('b','debit',250.0,'Chai',2000,750.0,'cash')");
        raw.execSQL("PRAGMA user_version = 2");
        raw.close();

        db = new DatabaseHelper(context);   // triggers onUpgrade 2 -> 3

        assertEquals(750.0,   db.getBalance(),     DELTA);
        assertEquals(-250.0,  db.getCashBalance(), DELTA);
        assertEquals(1000.0,  db.getBankBalance(), DELTA);

        // Pre-existing rows must land as ordinary, non-transfer records.
        assertEquals(DatabaseHelper.KIND_NORMAL, db.getTransaction("a").getKind());
        assertNull(db.getTransaction("a").getTransferId());
    }
```

> This test also depends on Task 2's `getKind()` / `getTransferId()`. Write the whole test now; the last two assertions will not compile until Task 2. To keep Task 1 green on its own, **comment out the final two assertions** and uncomment them in Task 2 Step 1.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DatabaseHelperTest.upgradingFromV2PreservesEveryBalance"`

Expected: FAIL — the helper is still at version 2, so no upgrade runs and the assertions on a v2-shaped table behave inconsistently. (Once at version 3 without the ALTERs it will fail with `no such column: kind`.)

- [ ] **Step 3: Add the columns and the migration**

In `DatabaseHelper.java`, bump the version:

```java
    private static final int    DATABASE_VERSION = 3;
```

Add beside the other column-name constants:

```java
    private static final String COL_KIND       = "kind";
    private static final String COL_TRANSFERID = "transferId";

    /** An ordinary income or expense row. */
    static final String KIND_NORMAL   = "normal";
    /** One leg of a cash<->bank transfer. Always paired via transferId. */
    static final String KIND_TRANSFER = "transfer";
```

Extend `onCreate`'s table definition — replace the `COL_PAYMENT` line and closing paren:

```java
                + COL_PAYMENT + " TEXT DEFAULT 'cash', "
                + COL_KIND    + " TEXT NOT NULL DEFAULT '" + KIND_NORMAL + "', "
                + COL_TRANSFERID + " TEXT)";
```

Extend `onUpgrade` with an independent `if` — **not** `else if`, so a v1 device runs both blocks in order and lands correctly on v3:

```java
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_KIND
                    + " TEXT NOT NULL DEFAULT '" + KIND_NORMAL + "'");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_TRANSFERID + " TEXT");
        }
```

In `insertTransaction`, tag the row explicitly rather than leaning on the column default:

```java
            values.put(COL_KIND, KIND_NORMAL);
            // transferId deliberately left NULL — this is not a transfer leg.
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS. 20 tests in `DatabaseHelperTest` (19 existing + the new one) plus `ExampleUnitTest`. Existing tests must all still pass — the new columns change no balance.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: add kind and transferId columns with a v3 migration"
```

---

## Task 2: Transaction model gains the transfer fields

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/Transaction.java`
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java` (`cursorToTransaction` only)
- Test: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `DatabaseHelper.KIND_NORMAL` from Task 1.
- Produces: `Transaction` constructor
  `Transaction(String id, String type, double amount, String description, long dateTime, double balanceAfterTransaction, String paymentMethod, String kind, String transferId, String fromMethod, String toMethod)`
  and getters `getKind()`, `getTransferId()`, `getFromMethod()`, `getToMethod()`.

- [ ] **Step 1: Write the failing test**

Uncomment the two assertions parked at the end of `upgradingFromV2PreservesEveryBalance` in Task 1, and add:

```java
    @Test
    public void anOrdinaryRowReportsItselfAsNormalAndUnpaired() {
        db.insertTransaction("debit", 250.0, "Chai", "cash");

        Transaction t = db.getTransactions(0, 10).get(0);

        assertEquals(DatabaseHelper.KIND_NORMAL, t.getKind());
        assertNull(t.getTransferId());
        assertNull("a single row has no direction", t.getFromMethod());
        assertNull("a single row has no direction", t.getToMethod());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DatabaseHelperTest.anOrdinaryRowReportsItselfAsNormalAndUnpaired"`

Expected: FAIL — compile error, `cannot find symbol: method getKind()`.

- [ ] **Step 3: Widen the model**

`Transaction.java` — there is exactly one caller of the constructor (`DatabaseHelper.cursorToTransaction`), so widening it in place is safe. Replace the fields, constructor, and getters:

```java
public class Transaction {
    private String id;
    private String type; // "credit" or "debit"
    private double amount;
    private String description;
    private long dateTime;  // Unix timestamp in milliseconds
    private double balanceAfterTransaction;
    private String paymentMethod; // "cash" or "bank"
    private String kind;          // "normal" or "transfer"
    private String transferId;    // non-null only on a transfer

    // Direction, populated only by the grouped history query. A single row read
    // on its own cannot know a direction — only a collapsed pair can.
    private String fromMethod;
    private String toMethod;

    public Transaction(String id, String type, double amount, String description,
                       long dateTime, double balanceAfterTransaction, String paymentMethod,
                       String kind, String transferId, String fromMethod, String toMethod) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.dateTime = dateTime;
        this.balanceAfterTransaction = balanceAfterTransaction;
        this.paymentMethod = paymentMethod;
        this.kind = kind;
        this.transferId = transferId;
        this.fromMethod = fromMethod;
        this.toMethod = toMethod;
    }

    /**
     * True when this row represents a complete transfer.
     * Deliberately stricter than checking kind: an orphaned leg (a transfer row
     * whose partner is missing) has kind 'transfer' but only one direction, and
     * must degrade to rendering as an ordinary row rather than showing "null".
     */
    public boolean isTransfer() {
        return fromMethod != null && toMethod != null;
    }

    public String getId()                       { return id; }
    public String getType()                     { return type; }
    public double getAmount()                   { return amount; }
    public String getDescription()              { return description; }
    public long getDateTime()                   { return dateTime; }
    public double getBalanceAfterTransaction()  { return balanceAfterTransaction; }
    public String getPaymentMethod()            { return paymentMethod; }
    public String getKind()                     { return kind; }
    public String getTransferId()               { return transferId; }
    public String getFromMethod()               { return fromMethod; }
    public String getToMethod()                 { return toMethod; }
}
```

In `DatabaseHelper.cursorToTransaction`, pass the two new row-level columns and NULL for the direction pair:

```java
        return new Transaction(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_DESC)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE)),
                paymentMethod,
                cursor.getString(cursor.getColumnIndexOrThrow(COL_KIND)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSFERID)),
                null,   // direction is only knowable from the grouped query
                null
        );
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS, 21 tests in `DatabaseHelperTest` plus `ExampleUnitTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/Transaction.java app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: carry kind, transferId and direction on the Transaction model"
```

---

## Task 3: `insertTransfer`

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Test: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `KIND_TRANSFER` (Task 1), the widened `Transaction` (Task 2).
- Produces: `boolean insertTransfer(double amount, String description, String fromMethod, String toMethod)`.

After this task history still shows two separate rows per transfer — the collapse arrives in Task 4. That intermediate state is expected and correct.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void aTransferLeavesTheTotalBalanceUntouched() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");

        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        assertEquals("a transfer creates and destroys no money",
                5000.0, db.getBalance(), DELTA);
    }

    @Test
    public void aTransferMovesMoneyFromCashToBank() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");

        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        assertEquals(3000.0, db.getCashBalance(), DELTA);
        assertEquals(2000.0, db.getBankBalance(), DELTA);
    }

    @Test
    public void aTransferWritesTwoTaggedLegsSharingOneId() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        List<Transaction> rows = db.getTransactions(0, 10);
        assertEquals("two legs on disk before grouping lands", 2, rows.size());

        for (Transaction row : rows) {
            assertEquals(DatabaseHelper.KIND_TRANSFER, row.getKind());
            assertNotNull(row.getTransferId());
        }
        assertEquals("both legs share one transferId",
                rows.get(0).getTransferId(), rows.get(1).getTransferId());
        assertEquals("both legs share one timestamp",
                rows.get(0).getDateTime(), rows.get(1).getDateTime());
    }

    @Test
    public void theCreditLegIsTheLaterOfTheTwo() {
        // Load-bearing: the collapsed history row reads its running balance from
        // the credit leg, which is only the post-transfer figure if that leg is
        // the later one. getTransactions returns newest-first, so index 0 here.
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        List<Transaction> rows = db.getTransactions(0, 10);
        assertEquals("credit leg must sort first when newest-first",
                "credit", rows.get(0).getType());
        assertEquals("debit", rows.get(1).getType());
    }
```

Add these imports to the test file if not already present:

```java
import static org.junit.Assert.assertNotNull;
import java.util.List;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DatabaseHelperTest.aTransfer*" --tests "*DatabaseHelperTest.theCreditLeg*"`

Expected: FAIL — compile error, `cannot find symbol: method insertTransfer`.

- [ ] **Step 3: Implement**

Add to `DatabaseHelper.java`, after `insertTransaction`:

```java
    // ─── TRANSFERS ───────────────────────────────────────────────────────────

    /**
     * Records a cash<->bank transfer as two paired rows, then rebuilds the cache.
     *
     * The two legs share one transferId and one dateTime. Because each leg carries
     * an ordinary type and paymentMethod, every balance query keeps working with no
     * knowledge of transfers at all: the debit leg lands in the source method's sum,
     * the credit leg in the destination's, and the two cancel in the total.
     *
     * ORDER IS LOAD-BEARING: the debit leg is inserted first so the credit leg
     * always holds the higher rowid. getTransactions reads the collapsed row's
     * running balance from the credit leg, which is the post-transfer figure only
     * if that leg is the later of the two. Swapping these two calls would leave
     * every transfer row in history showing a balance short by the transfer amount,
     * with every other figure on screen still correct.
     *
     * @param fromMethod "cash" or "bank" — where the money leaves
     * @param toMethod   "cash" or "bank" — where it arrives
     * @return true if both legs were written
     */
    public boolean insertTransfer(double amount, String description,
                                  String fromMethod, String toMethod) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            String transferId = UUID.randomUUID().toString();
            long   dateTime   = System.currentTimeMillis();

            if (!insertLeg(db, transferId, "debit",  amount, description, fromMethod, dateTime)) {
                return false;
            }
            if (!insertLeg(db, transferId, "credit", amount, description, toMethod, dateTime)) {
                return false;
            }

            recomputeBalanceCache(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Writes one leg of a transfer. Callers must already hold a database transaction. */
    private boolean insertLeg(SQLiteDatabase db, String transferId, String type, double amount,
                              String description, String paymentMethod, long dateTime) {
        ContentValues values = new ContentValues();
        values.put(COL_ID,         UUID.randomUUID().toString());
        values.put(COL_TYPE,       type);
        values.put(COL_AMOUNT,     amount);
        values.put(COL_DESC,       description);
        values.put(COL_DATE,       dateTime);
        values.put(COL_BALANCE,    0);   // placeholder; rebuilt by the caller
        values.put(COL_PAYMENT,    paymentMethod);
        values.put(COL_KIND,       KIND_TRANSFER);
        values.put(COL_TRANSFERID, transferId);
        return db.insert(TABLE, null, values) != -1;
    }
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS, 25 tests in `DatabaseHelperTest` plus `ExampleUnitTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: record a cash-bank transfer as two paired transaction rows"
```

---

## Task 4: Grouped history read path

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Test: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `insertTransfer` (Task 3), the widened `Transaction` (Task 2).
- Produces: `getTransactions(int, int)` now returns collapsed rows carrying `fromMethod`/`toMethod`; `getTotalCount()` counts groups; new `Transaction getTransfer(String transferId)`.

This is the only place that pays for the paired-row model.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void historyCollapsesATransferIntoOneRow() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        List<Transaction> rows = db.getTransactions(0, 10);

        assertEquals("one salary row plus one transfer row", 2, rows.size());
        Transaction transfer = rows.get(0);
        assertTrue(transfer.isTransfer());
        assertEquals(2000.0, transfer.getAmount(), DELTA);
        assertEquals("Move to savings", transfer.getDescription());
        assertEquals("cash", transfer.getFromMethod());
        assertEquals("bank", transfer.getToMethod());
    }

    @Test
    public void aCollapsedTransferShowsThePostTransferBalance() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        Transaction transfer = db.getTransactions(0, 10).get(0);

        // A transfer nets to zero, so the running balance is unchanged by it.
        assertEquals(5000.0, transfer.getBalanceAfterTransaction(), DELTA);
    }

    @Test
    public void aCollapsedTransferIsIdentifiedByItsTransferId() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        Transaction transfer = db.getTransactions(0, 10).get(0);

        assertNotNull(transfer.getTransferId());
        assertEquals("the collapsed row is keyed by transferId, not a leg id",
                transfer.getTransferId(), transfer.getId());
    }

    @Test
    public void getTotalCountCountsATransferAsOne() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        assertEquals("two legs are one history entry", 2, db.getTotalCount());
    }

    @Test
    public void paginationAcrossATransferNeitherSkipsNorDuplicates() {
        // Three entries, one of which is a transfer, read one page at a time.
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        db.insertTransaction("debit", 250.0, "Chai", "cash");

        List<Transaction> all = new ArrayList<>();
        for (int offset = 0; offset < db.getTotalCount(); offset += 1) {
            all.addAll(db.getTransactions(offset, 1));
        }

        assertEquals(3, all.size());
        Set<String> ids = new HashSet<>();
        for (Transaction t : all) {
            assertTrue("no id may repeat across pages", ids.add(t.getId()));
        }
        assertEquals("paged reads must match a single unpaged read",
                db.getTransactions(0, 100).size(), all.size());
    }

    @Test
    public void getTransferLoadsATransferByItsId() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();

        Transaction loaded = db.getTransfer(transferId);

        assertNotNull(loaded);
        assertEquals(2000.0, loaded.getAmount(), DELTA);
        assertEquals("cash", loaded.getFromMethod());
        assertEquals("bank", loaded.getToMethod());
    }

    @Test
    public void getTransferOnUnknownIdReturnsNull() {
        assertNull(db.getTransfer("no-such-transfer"));
    }

    @Test
    public void anOrphanedLegDegradesToAnOrdinaryRow() {
        // Reachable only through a bug or a manual database edit. It must not
        // crash the read path or render "cash -> null".
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();
        db.getWritableDatabase().execSQL(
                "DELETE FROM transactions WHERE transferId = ? AND type = 'credit'",
                new Object[]{transferId});

        List<Transaction> rows = db.getTransactions(0, 10);

        assertEquals(1, rows.size());
        assertFalse("a lone leg is not a complete transfer", rows.get(0).isTransfer());
        assertEquals("debit", rows.get(0).getType());
        assertEquals("cash", rows.get(0).getPaymentMethod());
    }
```

Add these imports to the test file if not already present:

```java
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DatabaseHelperTest.historyCollapses*" --tests "*DatabaseHelperTest.getTransfer*" --tests "*DatabaseHelperTest.pagination*"`

Expected: FAIL — compile error `cannot find symbol: method getTransfer`, and the collapse assertions fail because `getTransactions` still returns two rows.

- [ ] **Step 3: Implement the grouped projection**

Add to `DatabaseHelper.java`, near `signedSum`:

```java
    /**
     * One history entry per user action: an ordinary row is a group of one, a
     * transfer is its two legs collapsed into a single row.
     *
     * Every column is wrapped in an aggregate on purpose. SQLite has a documented
     * extension where a bare column selected alongside MAX() takes its value from
     * the matching row, which would express some of this more briefly — but relying
     * on a dialect extension to pick which row a money figure comes from is not
     * worth the saved characters.
     *
     * amount, description and dateTime are written identically to both legs, so
     * MAX() over them is exact rather than approximate. fromMethod and toMethod are
     * NULL for a group of one, which is how a caller tells a transfer from an
     * ordinary row without a second query.
     *
     * The running balance is read from the credit leg, which is the post-transfer
     * figure only because insertTransfer always writes the debit leg first.
     */
    private static final String GROUPED_SELECT =
            "SELECT "
            + "COALESCE(t." + COL_TRANSFERID + ", t." + COL_ID + ") AS " + COL_ID + ", "
            + "MAX(t." + COL_TYPE + ")    AS " + COL_TYPE + ", "
            + "MAX(t." + COL_AMOUNT + ")  AS " + COL_AMOUNT + ", "
            + "MAX(t." + COL_DESC + ")    AS " + COL_DESC + ", "
            + "MAX(t." + COL_DATE + ")    AS " + COL_DATE + ", "
            + "MAX(t." + COL_PAYMENT + ") AS " + COL_PAYMENT + ", "
            + "MAX(t." + COL_KIND + ")    AS " + COL_KIND + ", "
            + "MAX(t." + COL_TRANSFERID + ") AS " + COL_TRANSFERID + ", "
            + "MAX(CASE WHEN t." + COL_TYPE + " = 'debit'  THEN t." + COL_PAYMENT + " END) AS fromMethod, "
            + "MAX(CASE WHEN t." + COL_TYPE + " = 'credit' THEN t." + COL_PAYMENT + " END) AS toMethod, "
            + "CASE WHEN MAX(t." + COL_TRANSFERID + ") IS NULL "
            + "     THEN MAX(t." + COL_BALANCE + ") "
            + "     ELSE MAX(CASE WHEN t." + COL_TYPE + " = 'credit' THEN t." + COL_BALANCE + " END) "
            + "END AS " + COL_BALANCE + " "
            + "FROM " + TABLE + " t "
            + "GROUP BY COALESCE(t." + COL_TRANSFERID + ", t." + COL_ID + ")";
```

> **The `t.` qualifiers are not decoration.** The projection aliases
> `COALESCE(transferId, id)` to the output name `id`, and the `GROUP BY` also
> mentions `id`. Unqualified, that is ambiguous between the source column and
> the output alias — and if SQLite resolved it to the alias, the grouping key
> would reference itself. Qualifying against the table alias `t` makes every
> column reference unambiguously a source column. Do not remove them.

Replace the body of `getTransactions`:

```java
    public List<Transaction> getTransactions(int offset, int limit) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                GROUPED_SELECT
                        + " ORDER BY MAX(t." + COL_DATE + ") DESC, MAX(t.rowid) DESC"
                        + " LIMIT " + limit + " OFFSET " + offset, null);
        try {
            while (cursor.moveToNext()) {
                list.add(cursorToGroupedTransaction(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }
```

Add the grouped mapper beside `cursorToTransaction`:

```java
    /** Maps a row of GROUPED_SELECT. Distinct from cursorToTransaction, whose
     *  SELECT * has no fromMethod/toMethod columns to read. */
    private Transaction cursorToGroupedTransaction(Cursor cursor) {
        String paymentMethod = cursor.getString(cursor.getColumnIndexOrThrow(COL_PAYMENT));
        if (paymentMethod == null) paymentMethod = "cash"; // Default for old records

        return new Transaction(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_DESC)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE)),
                paymentMethod,
                cursor.getString(cursor.getColumnIndexOrThrow(COL_KIND)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSFERID)),
                cursor.getString(cursor.getColumnIndexOrThrow("fromMethod")),
                cursor.getString(cursor.getColumnIndexOrThrow("toMethod"))
        );
    }

    /**
     * Returns a transfer as one collapsed row, or null if there is no such transfer.
     * Used by the edit form to prefill.
     */
    public Transaction getTransfer(String transferId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                GROUPED_SELECT + " HAVING MAX(t." + COL_TRANSFERID + ") = ?",
                new String[]{transferId});
        try {
            return cursor.moveToFirst() ? cursorToGroupedTransaction(cursor) : null;
        } finally {
            cursor.close();
        }
    }
```

Replace the body of `getTotalCount` — it must count groups, or `LIMIT`/`OFFSET` will duplicate and skip rows across page boundaries, which is the Phase 1 pagination bug returning through a different door:

```java
    public int getTotalCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + TABLE
                        + " GROUP BY COALESCE(" + COL_TRANSFERID + ", " + COL_ID + "))", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS, 33 tests in `DatabaseHelperTest` plus `ExampleUnitTest`.

> **If `theCreditLegIsTheLaterOfTheTwo` (Task 3) now fails**, that is correct and expected — it asserted on the pre-collapse two-row shape. Update it to read the legs directly rather than through the collapsed query:
>
> ```java
>     @Test
>     public void theCreditLegIsTheLaterOfTheTwo() {
>         db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
>
>         Cursor c = db.getReadableDatabase().rawQuery(
>                 "SELECT type FROM transactions ORDER BY rowid ASC", null);
>         try {
>             c.moveToNext();
>             assertEquals("debit leg must be written first", "debit", c.getString(0));
>             c.moveToNext();
>             assertEquals("credit leg must be written second", "credit", c.getString(0));
>         } finally {
>             c.close();
>         }
>     }
> ```
>
> This needs `import android.database.Cursor;` in the test file.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: collapse transfer legs into one history row and count groups"
```

---

## Task 5: Transfer edit, delete, and leg guards

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/DatabaseHelper.java`
- Test: `app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java`

**Interfaces:**
- Consumes: `insertTransfer` (Task 3), `getTransfer` (Task 4).
- Produces: `boolean updateTransfer(String transferId, double amount, String description, String fromMethod, String toMethod)`, `boolean deleteTransfer(String transferId)`; and `updateTransaction`/`deleteTransaction` now reject transfer legs.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void editingATransferAmountMovesBothLegs() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();

        assertTrue(db.updateTransfer(transferId, 3000.0, "Move to savings", "cash", "bank"));

        assertEquals(2000.0, db.getCashBalance(), DELTA);
        assertEquals(3000.0, db.getBankBalance(), DELTA);
        assertEquals("still no money created or destroyed",
                5000.0, db.getBalance(), DELTA);
    }

    @Test
    public void flippingATransferDirectionReversesItsEffect() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();

        assertTrue(db.updateTransfer(transferId, 2000.0, "Move to savings", "bank", "cash"));

        assertEquals(7000.0, db.getCashBalance(), DELTA);
        assertEquals(-2000.0, db.getBankBalance(), DELTA);
        Transaction reloaded = db.getTransfer(transferId);
        assertEquals("bank", reloaded.getFromMethod());
        assertEquals("cash", reloaded.getToMethod());
    }

    @Test
    public void editingATransferPreservesItsTimestampAndPairing() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        Transaction before = db.getTransactions(0, 10).get(0);

        db.updateTransfer(before.getTransferId(), 3000.0, "Renamed", "cash", "bank");

        Transaction after = db.getTransactions(0, 10).get(0);
        assertEquals("editing must not move the row in history",
                before.getDateTime(), after.getDateTime());
        assertTrue("both legs must survive an edit", after.isTransfer());
        assertEquals("Renamed", after.getDescription());
    }

    @Test
    public void updatingAnUnknownTransferReturnsFalse() {
        assertFalse(db.updateTransfer("no-such-transfer", 100.0, "x", "cash", "bank"));
    }

    @Test
    public void deletingATransferRemovesBothLegs() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();

        assertTrue(db.deleteTransfer(transferId));

        assertEquals(1, db.getTotalCount());
        assertEquals(5000.0, db.getCashBalance(), DELTA);
        assertEquals(0.0, db.getBankBalance(), DELTA);
        assertEquals(5000.0, db.getBalance(), DELTA);
    }

    @Test
    public void deletingAnUnknownTransferReturnsFalse() {
        assertFalse(db.deleteTransfer("no-such-transfer"));
    }

    @Test
    public void aTransferLegCannotBeEditedAsAnOrdinaryTransaction() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        // Reach past the grouped query to get a real leg id.
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id FROM transactions LIMIT 1", null);
        c.moveToFirst();
        String legId = c.getString(0);
        c.close();

        assertFalse("editing one leg would orphan the other",
                db.updateTransaction(legId, "debit", 99.0, "hijacked", "cash"));
        assertEquals("balances must be untouched", 0.0, db.getBalance(), DELTA);
    }

    @Test
    public void aTransferLegCannotBeDeletedAsAnOrdinaryTransaction() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id FROM transactions WHERE transferId IS NOT NULL LIMIT 1", null);
        c.moveToFirst();
        String legId = c.getString(0);
        c.close();

        assertFalse("deleting one leg would orphan the other",
                db.deleteTransaction(legId));
        assertEquals(3000.0, db.getCashBalance(), DELTA);
        assertEquals(2000.0, db.getBankBalance(), DELTA);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DatabaseHelperTest.*Transfer*" --tests "*DatabaseHelperTest.flipping*"`

Expected: FAIL — compile error `cannot find symbol: method updateTransfer`.

- [ ] **Step 3: Implement**

Add to `DatabaseHelper.java` after `insertLeg`:

```java
    /**
     * Rewrites both legs of a transfer in place, then rebuilds the cache.
     *
     * Reversing direction is a swap of paymentMethod between the two legs, never a
     * delete-and-reinsert — so rowids survive, and with them both the row's position
     * in history and insertTransfer's debit-before-credit ordering guarantee.
     * dateTime is preserved, matching updateTransaction.
     *
     * @return true only if both legs were found and updated
     */
    public boolean updateTransfer(String transferId, double amount, String description,
                                  String fromMethod, String toMethod) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            int out = updateLeg(db, transferId, "debit",  amount, description, fromMethod);
            int in  = updateLeg(db, transferId, "credit", amount, description, toMethod);
            if (out == 0 || in == 0) {
                return false;   // rolled back: setTransactionSuccessful not reached
            }
            recomputeBalanceCache(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Updates one leg of a transfer. Callers must already hold a database transaction. */
    private int updateLeg(SQLiteDatabase db, String transferId, String type,
                          double amount, String description, String paymentMethod) {
        ContentValues values = new ContentValues();
        values.put(COL_AMOUNT,  amount);
        values.put(COL_DESC,    description);
        values.put(COL_PAYMENT, paymentMethod);
        return db.update(TABLE, values,
                COL_TRANSFERID + " = ? AND " + COL_TYPE + " = ?",
                new String[]{transferId, type});
    }

    /**
     * Permanently removes both legs of a transfer, then rebuilds the cache.
     * There is no undo — callers must confirm with the user first.
     *
     * @return true if the transfer existed and was removed
     */
    public boolean deleteTransfer(String transferId) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.delete(TABLE, COL_TRANSFERID + " = ?", new String[]{transferId});
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

Now add the guards. In `updateTransaction`, change the `db.update` call so a transfer leg can never be matched:

```java
            // "AND transferId IS NULL" is a guard, not a filter: editing a single leg
            // of a transfer would silently orphan its partner and leave both cash and
            // bank wrong. Transfers are edited through updateTransfer.
            int rows = db.update(TABLE, values,
                    COL_ID + " = ? AND " + COL_TRANSFERID + " IS NULL",
                    new String[]{id});
```

And the same in `deleteTransaction`:

```java
            // Guard: see updateTransaction. Transfers are deleted through deleteTransfer.
            int rows = db.delete(TABLE,
                    COL_ID + " = ? AND " + COL_TRANSFERID + " IS NULL",
                    new String[]{id});
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS, 41 tests in `DatabaseHelperTest` plus `ExampleUnitTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/DatabaseHelper.java app/src/test/java/com/example/expensetracker/DatabaseHelperTest.java
git commit -m "feat: edit and delete transfers as a unit, and guard the legs"
```

---

## Task 6: `TransferActivity` and its layout

**Files:**
- Create: `app/src/main/res/layout/activity_transfer.xml`
- Create: `app/src/main/java/com/example/expensetracker/TransferActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `insertTransfer`, `updateTransfer`, `deleteTransfer`, `getTransfer`.
- Produces: an Activity reachable with an optional `"transferId"` string extra — absent means create mode, present means edit mode.

The whole task is UI, which the Robolectric suite does not cover; the deliverable is verified by building and by the manual pass in Task 9.

- [ ] **Step 1: Create the layout**

`app/src/main/res/layout/activity_transfer.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#0D1117">

    <!-- ── Top Bar ──────────────────────────────────────────────────────── -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#161B22"
        android:paddingVertical="18dp"
        android:paddingHorizontal="20dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/tvTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="TRANSFER"
            android:textColor="#E6EDF3"
            android:textSize="18sp"
            android:textStyle="bold"
            android:fontFamily="monospace"/>
    </LinearLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="20dp">

            <!-- ── Kind Badge ───────────────────────────────────────────── -->
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="⇄  TRANSFER — Not income or expense"
                android:textSize="14sp"
                android:textStyle="bold"
                android:fontFamily="monospace"
                android:textColor="#00D4AA"
                android:background="#0D2B2B"
                android:gravity="center"
                android:paddingVertical="14dp"
                android:layout_marginBottom="24dp" />

            <!-- ── Direction ────────────────────────────────────────────── -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Direction"
                android:textSize="12sp"
                android:textColor="#8B949E"
                android:textStyle="bold"
                android:fontFamily="monospace"
                android:layout_marginBottom="6dp"/>

            <RadioGroup
                android:id="@+id/rgDirection"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginBottom="22dp">

                <RadioButton
                    android:id="@+id/rbCashToBank"
                    android:layout_width="0dp"
                    android:layout_height="54dp"
                    android:layout_weight="1"
                    android:checked="true"
                    android:text="💵 → 🏦"
                    android:textSize="14sp"
                    android:textStyle="bold"
                    android:textColor="@drawable/radio_button_text_color"
                    android:fontFamily="monospace"
                    android:background="@drawable/radio_button_selector"
                    android:button="@null"
                    android:gravity="center"
                    android:layout_marginEnd="6dp"/>

                <RadioButton
                    android:id="@+id/rbBankToCash"
                    android:layout_width="0dp"
                    android:layout_height="54dp"
                    android:layout_weight="1"
                    android:text="🏦 → 💵"
                    android:textSize="14sp"
                    android:textStyle="bold"
                    android:textColor="@drawable/radio_button_text_color"
                    android:fontFamily="monospace"
                    android:background="@drawable/radio_button_selector"
                    android:button="@null"
                    android:gravity="center"
                    android:layout_marginStart="6dp"/>
            </RadioGroup>

            <!-- ── Amount ───────────────────────────────────────────────── -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Amount"
                android:textSize="12sp"
                android:textColor="#8B949E"
                android:textStyle="bold"
                android:fontFamily="monospace"
                android:layout_marginBottom="6dp"/>

            <EditText
                android:id="@+id/etAmount"
                android:layout_width="match_parent"
                android:layout_height="60dp"
                android:hint="0.00"
                android:inputType="numberDecimal"
                android:textSize="30sp"
                android:textStyle="bold"
                android:textColor="#E6EDF3"
                android:textColorHint="#484F58"
                android:paddingHorizontal="16dp"
                android:paddingVertical="12dp"
                android:background="#1C2128"
                android:layout_marginBottom="22dp" />

            <!-- ── Description ──────────────────────────────────────────── -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Description"
                android:textSize="12sp"
                android:textColor="#8B949E"
                android:textStyle="bold"
                android:fontFamily="monospace"
                android:layout_marginBottom="6dp"/>

            <EditText
                android:id="@+id/etDescription"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="What is this for?"
                android:inputType="textCapSentences"
                android:textSize="18sp"
                android:textColor="#E6EDF3"
                android:textColorHint="#484F58"
                android:paddingHorizontal="16dp"
                android:paddingVertical="14dp"
                android:background="#1C2128"
                android:layout_marginBottom="22dp" />

            <!-- ── Save / Update ────────────────────────────────────────── -->
            <Button
                android:id="@+id/btnDone"
                android:layout_width="match_parent"
                android:layout_height="60dp"
                android:text="SAVE TRANSFER"
                android:textSize="16sp"
                android:textStyle="bold"
                android:backgroundTint="#00D4AA"
                android:textColor="#0D1117" />

            <!-- ── Cancel ───────────────────────────────────────────────── -->
            <Button
                android:id="@+id/btnCancel"
                android:layout_width="match_parent"
                android:layout_height="52dp"
                android:text="Cancel"
                android:textSize="15sp"
                android:backgroundTint="#1C2128"
                android:textColor="#8B949E"
                android:layout_marginTop="10dp" />

            <!-- ── Delete (edit mode only) ──────────────────────────────── -->
            <Button
                android:id="@+id/btnDelete"
                android:layout_width="match_parent"
                android:layout_height="52dp"
                android:text="DELETE TRANSFER"
                android:textSize="15sp"
                android:textStyle="bold"
                android:backgroundTint="#2D0F0F"
                android:textColor="#F85149"
                android:visibility="gone"
                android:layout_marginTop="10dp" />

        </LinearLayout>
    </ScrollView>
</LinearLayout>
```

- [ ] **Step 2: Create the Activity**

`app/src/main/java/com/example/expensetracker/TransferActivity.java`:

```java
package com.example.expensetracker;

import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Moves money between cash and bank. A transfer is neither income nor expense,
 * so this screen is deliberately separate from AddTransactionActivity — which
 * already carries create mode, edit mode and the credit/debit toggle — and is
 * themed neutral teal rather than credit-green or debit-red.
 *
 * Launch with no extras for create mode, or with a "transferId" string extra to
 * edit an existing transfer.
 */
public class TransferActivity extends AppCompatActivity {

    private static final String TAG = "TransferActivity";

    private EditText    etAmount, etDescription;
    private RadioButton rbCashToBank;
    private Button      btnDone, btnDelete;
    private TextView    tvTitle;

    private String      editingId;       // transferId; null in create mode
    private Transaction editingOriginal; // null in create mode
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        dbHelper  = new DatabaseHelper(this);
        editingId = getIntent().getStringExtra("transferId");

        etAmount      = findViewById(R.id.etAmount);
        etDescription = findViewById(R.id.etDescription);
        rbCashToBank  = findViewById(R.id.rbCashToBank);
        RadioButton rbBankToCash = findViewById(R.id.rbBankToCash);
        tvTitle       = findViewById(R.id.tvTitle);
        btnDone       = findViewById(R.id.btnDone);
        btnDelete     = findViewById(R.id.btnDelete);
        Button btnCancel = findViewById(R.id.btnCancel);

        if (editingId != null) {
            try {
                editingOriginal = dbHelper.getTransfer(editingId);
            } catch (SQLiteException e) {
                Log.e(TAG, "Failed to load transfer", e);
                editingOriginal = null;
            }
        }

        if (editingOriginal == null) {
            // ── Create mode ──
            // Also reached when the transfer we meant to edit was deleted between
            // the tap and onCreate: degrade to a usable blank form.
            editingId = null;
            tvTitle.setText("TRANSFER");
            btnDone.setText("SAVE TRANSFER");
        } else {
            // ── Edit mode ──
            tvTitle.setText("EDIT TRANSFER");
            btnDone.setText("UPDATE TRANSFER");

            etAmount.setText(String.format(Locale.getDefault(), "%.2f",
                    editingOriginal.getAmount()));
            etDescription.setText(editingOriginal.getDescription());

            if ("bank".equals(editingOriginal.getFromMethod())) {
                rbBankToCash.setChecked(true);
            } else {
                rbCashToBank.setChecked(true);
            }

            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
        }

        btnDone.setOnClickListener(v   -> saveTransfer());
        btnCancel.setOnClickListener(v -> finish());
        etAmount.requestFocus();
    }

    private void saveTransfer() {
        String amountStr   = etAmount.getText().toString().trim();
        String description = etDescription.getText().toString().trim();

        if (TextUtils.isEmpty(amountStr)) {
            etAmount.setError("Please enter an amount");
            etAmount.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(description)) {
            etDescription.setError("Please enter a description");
            etDescription.requestFocus();
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            etAmount.setError("Invalid amount");
            return;
        }
        if (amount <= 0) {
            etAmount.setError("Amount must be greater than zero");
            return;
        }

        // The radio group offers only the two cross-method directions, so a
        // cash-to-cash transfer cannot be expressed and needs no validation.
        String fromMethod = rbCashToBank.isChecked() ? "cash" : "bank";
        String toMethod   = rbCashToBank.isChecked() ? "bank" : "cash";

        boolean ok;
        String successMessage;
        try {
            if (editingId == null) {
                ok = dbHelper.insertTransfer(amount, description, fromMethod, toMethod);
                successMessage = "✓ Transfer saved";
            } else {
                ok = dbHelper.updateTransfer(editingId, amount, description, fromMethod, toMethod);
                successMessage = "✓ Transfer updated";
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to save transfer", e);
            ok = false;
            successMessage = null;
        }

        if (ok) {
            Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error saving. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Deleting is irreversible and there is no undo, so name the transfer in the prompt. */
    private void confirmDelete() {
        if (editingOriginal == null) return;

        String summary = String.format(Locale.getDefault(), "₹ %.2f — %s",
                editingOriginal.getAmount(), editingOriginal.getDescription());

        new AlertDialog.Builder(this)
                .setTitle("Delete this transfer?")
                .setMessage(summary + "\n\nBoth sides of the transfer will be removed."
                        + "\nThis cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean deleted;
                    try {
                        deleted = dbHelper.deleteTransfer(editingId);
                    } catch (SQLiteException e) {
                        Log.e(TAG, "Failed to delete transfer", e);
                        deleted = false;
                    }
                    if (deleted) {
                        Toast.makeText(this, "✓ Transfer deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Error deleting. Try again.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
```

- [ ] **Step 3: Register the Activity**

In `app/src/main/AndroidManifest.xml`, add after the `AddTransactionActivity` block:

```xml
        <!-- Cash <-> bank transfer (auto-shows keyboard) -->
        <activity
            android:name=".TransferActivity"
            android:exported="false"
            android:windowSoftInputMode="stateVisible|adjustResize" />
```

- [ ] **Step 4: Verify it builds**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. Nothing reaches this screen yet — that is Tasks 7 and 8.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/TransferActivity.java app/src/main/res/layout/activity_transfer.xml app/src/main/AndroidManifest.xml
git commit -m "feat: add the transfer form with create, edit and delete modes"
```

---

## Task 7: Home screen entry point

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/expensetracker/MainActivity.java`

**Interfaces:**
- Consumes: `TransferActivity` (Task 6).
- Produces: `R.id.btnTransfer` on the home screen.

- [ ] **Step 1: Add the button to the layout**

In `activity_main.xml`, insert between the closing `</LinearLayout>` of the Credit/Debit row and the History button:

```xml
    <!-- ── Transfer Button ───────────────────────────────────────────────── -->
    <Button
        android:id="@+id/btnTransfer"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_marginHorizontal="16dp"
        android:layout_marginTop="12dp"
        android:text="⇄  Transfer"
        android:textSize="15sp"
        android:textStyle="bold"
        android:backgroundTint="#0D2B2B"
        android:textColor="#00D4AA" />
```

It sits on its own row rather than joining the Income/Expense pair: a transfer is not a third kind of spending, and filing it beside them is the confusion this phase exists to remove.

- [ ] **Step 2: Wire it up**

In `MainActivity.onCreate`, beside the other button lookups:

```java
        Button btnTransfer = findViewById(R.id.btnTransfer);
```

and beside the other listeners:

```java
        btnTransfer.setOnClickListener(v ->
                startActivity(new Intent(this, TransferActivity.class)));
```

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/example/expensetracker/MainActivity.java
git commit -m "feat: add a Transfer button to the home screen"
```

---

## Task 8: History rendering and routing

**Files:**
- Modify: `app/src/main/java/com/example/expensetracker/TransactionAdapter.java`
- Modify: `app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java`

**Interfaces:**
- Consumes: `Transaction.isTransfer()`, `getFromMethod()`, `getToMethod()`, `getTransferId()` (Task 2); `TransferActivity` (Task 6).
- Produces: nothing downstream.

- [ ] **Step 1: Render transfers neutrally**

In `TransactionAdapter.java`, add to the palette block:

```java
    private static final int COLOR_TRANSFER_TEXT = 0xFF00D4AA;  // teal — neither income nor expense
    private static final int COLOR_TRANSFER_BG   = 0xFF0D1A1A;  // very dark teal tint
```

Replace the `if (isCredit) { ... } else { ... }` block in `onBindViewHolder` and the payment-icon lines above it with:

```java
        if (t.isTransfer()) {
            // A transfer moves money without earning or spending it, so it gets
            // neither a sign nor a red/green colour.
            h.tvType.setText("⇄ TRANSFER");
            h.tvAmount.setText(String.format(Locale.getDefault(), "₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_TRANSFER_TEXT);
            h.tvAmount.setTextColor(COLOR_TRANSFER_TEXT);
            h.itemView.setBackgroundColor(COLOR_TRANSFER_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getFromMethod())
                    + " → " + methodIcon(t.getToMethod()));
        } else if (isCredit) {
            h.tvType.setText("▲ CREDIT");
            h.tvAmount.setText(String.format(Locale.getDefault(), "+ ₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_CREDIT_TEXT);
            h.tvAmount.setTextColor(COLOR_CREDIT_TEXT);
            h.itemView.setBackgroundColor(COLOR_CREDIT_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getPaymentMethod()));
        } else {
            h.tvType.setText("▼ DEBIT");
            h.tvAmount.setText(String.format(Locale.getDefault(), "- ₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_DEBIT_TEXT);
            h.tvAmount.setTextColor(COLOR_DEBIT_TEXT);
            h.itemView.setBackgroundColor(COLOR_DEBIT_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getPaymentMethod()));
        }
```

Delete the two now-superseded lines that used to set the icon before the branch:

```java
        // Set payment method icon
        String paymentIcon = "cash".equals(t.getPaymentMethod()) ? "💵" : "🏦";
        h.tvPaymentMethod.setText(" • " + paymentIcon);
```

Add the helper at the bottom of the class, beside `getItemCount`:

```java
    private static String methodIcon(String paymentMethod) {
        return "cash".equals(paymentMethod) ? "💵" : "🏦";
    }
```

- [ ] **Step 2: Route taps by kind**

In `TransactionHistoryActivity.java`, replace `openForEdit`:

```java
    private void openForEdit(Transaction transaction) {
        // A transfer is two rows collapsed into one; it is edited as a unit on a
        // different screen, keyed by transferId rather than by a leg's id.
        if (transaction.isTransfer()) {
            Intent intent = new Intent(this, TransferActivity.class);
            intent.putExtra("transferId", transaction.getTransferId());
            startActivity(intent);
            return;
        }

        Intent intent = new Intent(this, AddTransactionActivity.class);
        intent.putExtra("transactionId", transaction.getId());
        startActivity(intent);
    }
```

- [ ] **Step 3: Verify it builds and the suite still passes**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`, 41 tests plus `ExampleUnitTest` passing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/expensetracker/TransactionAdapter.java app/src/main/java/com/example/expensetracker/TransactionHistoryActivity.java
git commit -m "feat: render transfers as one neutral row and route their edits"
```

---

## Task 9: Full verification

**Files:** none modified unless a check fails.

**Interfaces:**
- Consumes: everything above.
- Produces: a verified, releasable branch.

- [ ] **Step 1: Run the whole unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS. **41** tests in `DatabaseHelperTest` (19 from Phase 1 plus 22 added here) plus the generated `ExampleUnitTest`.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew clean :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. The `clean` is not optional — this project has produced an `UP-TO-DATE` build with a stale APK on disk before, which looks exactly like a failed merge.

- [ ] **Step 3: Confirm no constraint was violated**

```bash
grep -n "DATABASE_VERSION" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
grep -n "db.close()" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
grep -c "signedSum" app/src/main/java/com/example/expensetracker/DatabaseHelper.java
git diff main --stat -- app/src/main/java/com/example/expensetracker/DatabaseHelper.java
```

Expected: `DATABASE_VERSION` is `3`; `db.close()` produces no output; `signedSum` appears 4 times (one definition, three call sites) — unchanged from Phase 1. Confirm by reading the diff that `recomputeBalanceCache`, `getBalance`, `getCashBalance`, and `getBankBalance` have no changes to their bodies.

- [ ] **Step 4: Verify the APK really contains the change**

```bash
cd app/build/outputs/apk/debug && unzip -o -q app-debug.apk 'classes*.dex' && grep -c "insertTransfer" classes*.dex
```

Expected: at least one match. Grepping the `.apk` directly finds nothing — code lives in compressed `classes.dex` inside the zip — so a bare grep on the apk is a false negative, not evidence of a bad build.

- [ ] **Step 5: Manual check on a device**

Install with `adb install -r <apk>` (the `-r` preserves app data, which is what makes the upgrade check below possible). `adb` is not on PATH; it lives at `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.

Record the result of each:

1. Home screen shows the new `⇄ Transfer` button between the Income/Expense pair and History.
2. Add ₹5,000 income to cash. Transfer ₹2,000 cash → bank. Cash reads ₹3,000, bank reads ₹2,000, and the **headline total is unchanged at ₹5,000**.
3. Open history. The transfer is **one** teal row reading `⇄ TRANSFER`, with `💵 → 🏦`, an unsigned amount, and a running balance equal to the total before it.
4. Tap the transfer row. The form opens titled `EDIT TRANSFER`, prefilled with amount, description and direction; the primary button reads `UPDATE TRANSFER`; Delete is visible.
5. Change the amount to ₹3,000 and save. Cash and bank both move, the total stays ₹5,000, and the row keeps its position in history.
6. Edit the transfer and flip the direction to `🏦 → 💵`. Both balances reverse; the total is still unchanged.
7. Delete the transfer. Confirm the dialog names the right amount and says both sides will be removed. After deleting, cash is back to ₹5,000, bank to ₹0, and history shows only the income row.
8. Add a transfer, then a normal expense, then delete the transfer. Every per-row balance in history and the headline balance are correct.
9. Income, Expense and History all behave exactly as they did before — no transfer UI leaks into them, and tapping an ordinary row still opens `EDIT`, not `EDIT TRANSFER`.

- [ ] **Step 6: Verify existing data survives the upgrade**

The migration adds two columns with defaults and rewrites no balances, so pre-existing rows should read identically. On a device that already holds real data from before this branch, note the home-screen balances and the top few history figures **before** installing, then install over it with `adb install -r` and confirm every figure is unchanged and history still groups one row per old transaction.

- [ ] **Step 7: Commit anything the checks turned up**

If Steps 1–6 were all clean, there is nothing to commit and Phase 2 is done. If a fix was needed, commit it with a `fix:` message describing what the verification caught.

---

## Notes for whoever executes this

**Escalate rather than work around.** If a test is flaky, or a step's expected output does not match what you see, stop and report BLOCKED with the actual output. Phase 1's most important fix — the rowid tie-breaker — exists only because an implementer escalated a flaky test instead of retrying until it passed.

**The debit-before-credit rule in Task 3 is the one thing that fails silently.** Break it and the app still runs, every balance on the home screen is still right, and only the running-balance column on transfer rows is wrong — by exactly the transfer amount. If a transfer row's balance ever looks off by its own amount, check the insert order first.

**Two known issues are deliberately out of scope**, both recorded as Phase 1 follow-ups: `DatabaseHelper` is constructed per Activity rather than being application-scoped (`TransferActivity` adds a fourth site), and `recomputeBalanceCache` is O(n²) row visits per mutation (a transfer costs two rows instead of one). Do not fix either here; they deserve their own review.
