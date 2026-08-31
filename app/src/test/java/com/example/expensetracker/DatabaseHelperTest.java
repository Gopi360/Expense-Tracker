package com.example.expensetracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Test
    public void rapidInsertsKeepPerRowBalancesConsistent() {
        for (int i = 0; i < 50; i++) {
            String type = (i % 2 == 0) ? "credit" : "debit";
            db.insertTransaction(type, i + 1.0, "Row " + i, "cash");
        }

        java.util.List<Transaction> all = db.getTransactions(0, 50);
        assertEquals(50, all.size());

        // Walk from the oldest row (bottom of the newest-first list) upward,
        // accumulating the running balance and checking it against the cache
        // at every step. Holds regardless of how many rows share a dateTime.
        double running = 0.0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Transaction t = all.get(i);
            running += "credit".equals(t.getType()) ? t.getAmount() : -t.getAmount();
            assertEquals(running, t.getBalanceAfterTransaction(), DELTA);
        }
    }

    @Test
    public void tiedTimestampsOrderAndAccumulateByInsertionOrder() {
        db.insertTransaction("credit", 100.0, "A", "cash");
        db.insertTransaction("debit",   40.0, "B", "cash");
        db.insertTransaction("credit",  10.0, "C", "cash");

        // Force an exact three-way dateTime tie — wall-clock inserts only hit this by luck.
        db.getWritableDatabase().execSQL("UPDATE transactions SET dateTime = 1000");

        // Any mutation re-runs the rebuild against the now-tied rows.
        assertTrue(db.insertTransaction("credit", 1.0, "D", "cash"));

        java.util.List<Transaction> all = db.getTransactions(0, 50);
        assertEquals("D", all.get(0).getDescription());   // pins DIRECTION: newest first
        assertEquals("C", all.get(1).getDescription());
        assertEquals("B", all.get(2).getDescription());
        assertEquals("A", all.get(3).getDescription());
        assertEquals( 71.0, all.get(0).getBalanceAfterTransaction(), DELTA); // pins CONSISTENCY
        assertEquals( 70.0, all.get(1).getBalanceAfterTransaction(), DELTA);
        assertEquals( 60.0, all.get(2).getBalanceAfterTransaction(), DELTA);
        assertEquals(100.0, all.get(3).getBalanceAfterTransaction(), DELTA);
    }

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

    @Test
    public void anOrdinaryRowReportsItselfAsNormalAndUnpaired() {
        db.insertTransaction("debit", 250.0, "Chai", "cash");

        Transaction t = db.getTransactions(0, 10).get(0);

        assertEquals(DatabaseHelper.KIND_NORMAL, t.getKind());
        assertNull(t.getTransferId());
        assertNull("a single row has no direction", t.getFromMethod());
        assertNull("a single row has no direction", t.getToMethod());
    }

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

        // Read the legs directly — getTransactions now collapses them into one row.
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT kind, transferId, dateTime FROM transactions ORDER BY rowid ASC", null);
        try {
            assertEquals("two legs on disk", 2, c.getCount());

            c.moveToNext();
            String kindA = c.getString(0), transferIdA = c.getString(1);
            long dateTimeA = c.getLong(2);
            c.moveToNext();

            assertEquals(DatabaseHelper.KIND_TRANSFER, kindA);
            assertEquals(DatabaseHelper.KIND_TRANSFER, c.getString(0));
            assertNotNull(transferIdA);
            assertEquals("both legs share one transferId", transferIdA, c.getString(1));
            assertEquals("both legs share one timestamp", dateTimeA, c.getLong(2));
        } finally {
            c.close();
        }
    }

    @Test
    public void theCreditLegIsTheLaterOfTheTwo() {
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");

        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT type FROM transactions ORDER BY rowid ASC", null);
        try {
            c.moveToNext();
            assertEquals("debit leg must be written first", "debit", c.getString(0));
            c.moveToNext();
            assertEquals("credit leg must be written second", "credit", c.getString(0));
        } finally {
            c.close();
        }
    }

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

    @Test
    public void deletingAnOrphanedTransferSucceedsAndClearsIt() {
        db.insertTransaction("credit", 5000.0, "Salary", "cash");
        db.insertTransfer(2000.0, "Move to savings", "cash", "bank");
        String transferId = db.getTransactions(0, 10).get(0).getTransferId();
        // Orphan it the same way anOrphanedLegDegradesToAnOrdinaryRow does: raw SQL,
        // bypassing the guards, so only the debit leg survives.
        db.getWritableDatabase().execSQL(
                "DELETE FROM transactions WHERE transferId = ? AND type = 'credit'",
                new Object[]{transferId});

        assertTrue("removing the broken leftover row is the desired outcome, not a failure",
                db.deleteTransfer(transferId));

        assertEquals(1, db.getTotalCount());
        assertEquals(5000.0, db.getCashBalance(), DELTA);
        assertEquals(0.0, db.getBankBalance(), DELTA);
        assertEquals(5000.0, db.getBalance(), DELTA);
    }

}
