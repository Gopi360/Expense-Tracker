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
    private static final int    DATABASE_VERSION = 3;

    // Table & column names
    private static final String TABLE  = "transactions";
    private static final String COL_ID      = "id";
    private static final String COL_TYPE    = "type";
    private static final String COL_AMOUNT  = "amount";
    private static final String COL_DESC    = "description";
    private static final String COL_DATE    = "dateTime";
    private static final String COL_BALANCE = "balanceAfter";
    private static final String COL_PAYMENT = "paymentMethod";
    private static final String COL_KIND       = "kind";
    private static final String COL_TRANSFERID = "transferId";

    /** An ordinary income or expense row. */
    static final String KIND_NORMAL   = "normal";
    /** One leg of a cash<->bank transfer. Always paired via transferId. */
    static final String KIND_TRANSFER = "transfer";

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
     * MAX() over them is exact rather than approximate.
     *
     * The direction pair is guarded on transferId, not just on type. Without that
     * guard an ordinary debit row would report fromMethod = its own paymentMethod,
     * because it matches the type arm on its own — leaving every plain expense
     * carrying half a direction it has no business having. Guarded, both are NULL
     * for any non-transfer row, which is how a caller tells a transfer from an
     * ordinary row without a second query. The guard tests transferId rather than
     * kind so that kind stays write-only in this phase, as the spec's Risks
     * section states.
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
            + "MAX(CASE WHEN t." + COL_TRANSFERID + " IS NOT NULL AND t." + COL_TYPE + " = 'debit'  "
            + "          THEN t." + COL_PAYMENT + " END) AS fromMethod, "
            + "MAX(CASE WHEN t." + COL_TRANSFERID + " IS NOT NULL AND t." + COL_TYPE + " = 'credit' "
            + "          THEN t." + COL_PAYMENT + " END) AS toMethod, "
            + "CASE WHEN MAX(t." + COL_TRANSFERID + ") IS NULL "
            + "     THEN MAX(t." + COL_BALANCE + ") "
            + "     ELSE MAX(CASE WHEN t." + COL_TYPE + " = 'credit' THEN t." + COL_BALANCE + " END) "
            + "END AS " + COL_BALANCE + " "
            + "FROM " + TABLE + " t "
            + "GROUP BY COALESCE(t." + COL_TRANSFERID + ", t." + COL_ID + ")";

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
                + COL_PAYMENT + " TEXT DEFAULT 'cash', "
                + COL_KIND    + " TEXT NOT NULL DEFAULT '" + KIND_NORMAL + "', "
                + COL_TRANSFERID + " TEXT)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Add paymentMethod column to existing table
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_PAYMENT + " TEXT DEFAULT 'cash'");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_KIND
                    + " TEXT NOT NULL DEFAULT '" + KIND_NORMAL + "'");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_TRANSFERID + " TEXT");
        }
    }

    // ─── INSERT ──────────────────────────────────────────────────────────────

    /**
     * Rebuilds the display-only balanceAfter column for every row, in one statement.
     * Must be called inside an open database transaction, after the mutation.
     * Nothing depends on this column for correctness — headline balances are computed.
     *
     * Both this method's correlation and getTransactions' ORDER BY break dateTime ties
     * with the bare SQLite keyword rowid (not a COL_* constant — it is not a schema
     * column). This is safe only because:
     *   - the table is an ordinary rowid table: id is TEXT PRIMARY KEY, which does not
     *     alias rowid (only INTEGER PRIMARY KEY does), so every row — including rows
     *     written by v1 of the app — has one.
     *   - among rows simultaneously present, rowid order equals insertion order, and
     *     this holds even after deletes: without AUTOINCREMENT, SQLite assigns
     *     max(rowid)+1 over rows currently present, so a freed number can never rank a
     *     new row below an older, still-present one.
     * A later phase adding WITHOUT ROWID or an INTEGER PRIMARY KEY id would silently
     * break both the running balance and pagination.
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
            values.put(COL_KIND, KIND_NORMAL);
            // transferId deliberately left NULL — this is not a transfer leg.

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

            // "AND transferId IS NULL" is a guard, not a filter: editing a single leg
            // of a transfer would silently orphan its partner and leave both cash and
            // bank wrong. Transfers are edited through updateTransfer.
            int rows = db.update(TABLE, values,
                    COL_ID + " = ? AND " + COL_TRANSFERID + " IS NULL",
                    new String[]{id});
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
            // Guard: see updateTransaction. Transfers are deleted through deleteTransfer.
            int rows = db.delete(TABLE,
                    COL_ID + " = ? AND " + COL_TRANSFERID + " IS NULL",
                    new String[]{id});
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
                paymentMethod,
                cursor.getString(cursor.getColumnIndexOrThrow(COL_KIND)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSFERID)),
                null,   // direction is only knowable from the grouped query
                null
        );
    }

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
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + TABLE
                        + " GROUP BY COALESCE(" + COL_TRANSFERID + ", " + COL_ID + "))", null);
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
