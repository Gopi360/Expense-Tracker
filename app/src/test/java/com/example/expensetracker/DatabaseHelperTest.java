package com.example.expensetracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

}
