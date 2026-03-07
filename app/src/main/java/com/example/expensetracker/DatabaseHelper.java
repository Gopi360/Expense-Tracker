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
    private static final int    DATABASE_VERSION = 1;

    // Table & column names
    private static final String TABLE  = "transactions";
    private static final String COL_ID      = "id";
    private static final String COL_TYPE    = "type";
    private static final String COL_AMOUNT  = "amount";
    private static final String COL_DESC    = "description";
    private static final String COL_DATE    = "dateTime";
    private static final String COL_BALANCE = "balanceAfter";

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
                + COL_BALANCE + " REAL NOT NULL)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ─── INSERT ──────────────────────────────────────────────────────────────

    /**
     * Insert a new transaction. Automatically calculates the new balance.
     * @return true if insert was successful
     */
    public boolean insertTransaction(String type, double amount, String description) {
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
                list.add(new Transaction(
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                        cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_DESC)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                        cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE))
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
}
