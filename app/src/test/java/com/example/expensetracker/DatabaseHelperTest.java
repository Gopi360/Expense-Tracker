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
