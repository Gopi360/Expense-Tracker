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
}
