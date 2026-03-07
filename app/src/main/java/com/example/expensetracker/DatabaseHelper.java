package com.example.expensetracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite database helper — manages all data storage for the app.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME    = "expense_tracker.db";
    private static final int    DATABASE_VERSION = 2;

    // Table & column names
    private static final String TABLE  = "transactions";
    private static final String COL_ID      = "id";
    private static final String COL_TYPE    = "type";
    private static final String COL_AMOUNT  = "amount";
    private static final String COL_DESC    = "description";
    private static final String COL_DATE    = "dateTime";
    private static final String COL_BALANCE = "balanceAfter";
    private static final String COL_PAYMENT = "paymentMethod";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE + " ("
                + COL_ID      + " TEXT PRIMARY KEY, "
                + COL_TYPE    + " TEXT NOT NULL, "
                + COL_AMOUNT  + " REAL NOT NULL, "
                + COL_DESC    + " TEXT, "
                + COL_DATE    + " INTEGER NOT NULL, "
                + COL_BALANCE + " REAL NOT NULL, "
                + COL_PAYMENT + " TEXT DEFAULT 'cash')";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Add paymentMethod column to existing table
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_PAYMENT + " TEXT DEFAULT 'cash'");
        }
    }

    // ─── INSERT ──────────────────────────────────────────────────────────────

    /**
     * Insert a new transaction. Automatically calculates the new balance.
     * @param paymentMethod "cash" or "bank"
     * @return true if insert was successful
     */
    public boolean insertTransaction(String type, double amount, String description, String paymentMethod) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            double currentBalance = getBalanceInternal(db);
            double newBalance = "credit".equals(type)
                    ? currentBalance + amount
                    : currentBalance - amount;

            ContentValues values = new ContentValues();
            values.put(COL_ID,      UUID.randomUUID().toString());
            values.put(COL_TYPE,    type);
            values.put(COL_AMOUNT,  amount);
            values.put(COL_DESC,    description);
            values.put(COL_DATE,    System.currentTimeMillis());
            values.put(COL_BALANCE, newBalance);
            values.put(COL_PAYMENT, paymentMethod);

            return db.insert(TABLE, null, values) != -1;
        } finally {
            db.close();
        }
    }

    // ─── QUERY ───────────────────────────────────────────────────────────────

    /**
     * Returns the current balance (balance after the most recent transaction).
     */
    public double getBalance() {
        SQLiteDatabase db = this.getReadableDatabase();
        try {
            return getBalanceInternal(db);
        } finally {
            db.close();
        }
    }

    private double getBalanceInternal(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery(
                "SELECT " + COL_BALANCE + " FROM " + TABLE
                        + " ORDER BY " + COL_DATE + " DESC LIMIT 1", null);
        double balance = 0;
        if (cursor.moveToFirst()) {
            balance = cursor.getDouble(0);
        }
        cursor.close();
        return balance;
    }

    /**
     * Returns a page of transactions, newest first.
     * @param offset number of records to skip
     * @param limit  max records to return
     */
    public List<Transaction> getTransactions(int offset, int limit) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE
                        + " ORDER BY " + COL_DATE + " DESC"
                        + " LIMIT " + limit + " OFFSET " + offset, null);
        try {
            while (cursor.moveToNext()) {
                String paymentMethod = cursor.getString(cursor.getColumnIndexOrThrow(COL_PAYMENT));
                if (paymentMethod == null) paymentMethod = "cash"; // Default for old records
                
                list.add(new Transaction(
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                        cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_DESC)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                        cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE)),
                        paymentMethod
                ));
            }
        } finally {
            cursor.close();
            db.close();
        }
        return list;
    }

    /**
     * Returns the total number of transactions stored.
     */
    public int getTotalCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }

    /**
     * Returns the cash balance (sum of credits minus debits for cash transactions).
     */
    public double getCashBalance() {
        return getBalanceByPaymentMethod("cash");
    }

    /**
     * Returns the bank balance (sum of credits minus debits for bank transactions).
     */
    public double getBankBalance() {
        return getBalanceByPaymentMethod("bank");
    }

    private double getBalanceByPaymentMethod(String paymentMethod) {
        SQLiteDatabase db = this.getReadableDatabase();
        try {
            // Calculate balance: sum of credits minus sum of debits
            Cursor cursor = db.rawQuery(
                    "SELECT " + COL_TYPE + ", SUM(" + COL_AMOUNT + ") FROM " + TABLE
                            + " WHERE " + COL_PAYMENT + " = ?"
                            + " GROUP BY " + COL_TYPE,
                    new String[]{paymentMethod});
            
            double credits = 0;
            double debits = 0;
            
            while (cursor.moveToNext()) {
                String type = cursor.getString(0);
                double sum = cursor.getDouble(1);
                if ("credit".equals(type)) {
                    credits = sum;
                } else if ("debit".equals(type)) {
                    debits = sum;
                }
            }
            cursor.close();
            return credits - debits;
        } finally {
            db.close();
        }
    }
}
