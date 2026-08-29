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

    /**
     * Signed sum of matching rows: credits add, debits subtract.
     * COALESCE because SUM over zero rows is NULL.
     *
     * @param qualifier table alias prefix ending in a dot (e.g. "t2."), or "" for an unqualified query
     */
    private static String signedSum(String qualifier) {
        return "COALESCE(SUM(CASE WHEN " + qualifier + COL_TYPE + " = 'credit'"
                + " THEN " + qualifier + COL_AMOUNT
                + " ELSE -" + qualifier + COL_AMOUNT + " END), 0)";
    }

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
     * Rebuilds the display-only balanceAfter column for every row, in one statement.
     * Must be called inside an open database transaction, after the mutation.
     * Nothing depends on this column for correctness — headline balances are computed.
     */
    private void recomputeBalanceCache(SQLiteDatabase db) {
        db.execSQL(
                "UPDATE " + TABLE + " SET " + COL_BALANCE + " = ("
                        + "SELECT " + signedSum("t2.")
                        + " FROM " + TABLE + " t2"
                        + " WHERE t2." + COL_DATE + " < " + TABLE + "." + COL_DATE
                        + " OR (t2." + COL_DATE + " = " + TABLE + "." + COL_DATE
                        + " AND t2.rowid <= " + TABLE + ".rowid))");
    }

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

    // ─── QUERY ───────────────────────────────────────────────────────────────

    /**
     * Returns the current total balance, computed from every row.
     * Never reads the balanceAfter cache — that column is display-only.
     */
    public double getBalance() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + signedSum("") + " FROM " + TABLE, null);
        try {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 0;
        } finally {
            cursor.close();
        }
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
                        + " ORDER BY " + COL_DATE + " DESC, rowid DESC"
                        + " LIMIT " + limit + " OFFSET " + offset, null);
        try {
            while (cursor.moveToNext()) {
                list.add(cursorToTransaction(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

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
        Cursor cursor = db.rawQuery(
                "SELECT " + signedSum("") + " FROM " + TABLE
                        + " WHERE COALESCE(" + COL_PAYMENT + ", 'cash') = ?",
                new String[]{paymentMethod});
        try {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 0;
        } finally {
            cursor.close();
        }
    }
}
