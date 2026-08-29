package com.example.expensetracker;

import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class AddTransactionActivity extends AppCompatActivity {

    private static final String TAG = "AddTransactionActivity";

    // Dark theme palette
    private static final int COLOR_GREEN       = 0xFF3FB950;
    private static final int COLOR_RED         = 0xFFF85149;
    private static final int COLOR_GREEN_DIM   = 0xFF0D2B1A;
    private static final int COLOR_RED_DIM     = 0xFF2D0F0F;
    private static final int COLOR_TEAL        = 0xFF00D4AA;

    private EditText       etAmount, etDescription;
    private RadioGroup     rgPaymentMethod, rgType;
    private RadioButton    rbCash, rbBank, rbCredit, rbDebit;
    private TextView       tvTitle, tvTypeLabel;
    private Button         btnDone, btnDelete;
    private String         transactionType;
    private String         editingId;       // null in create mode
    private Transaction    editingOriginal; // null in create mode
    private DatabaseHelper dbHelper;

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
            if (transactionType == null) {
                // Reached when the row we meant to edit was deleted elsewhere between the
                // tap and onCreate: degrade to create mode. This keeps the form usable
                // (transactionType feeds tvTitle/applyTypeTheme, which don't expect null)
                // and keeps transactionType provably one of "credit"/"debit" from here on.
                transactionType = "debit";
            }
            tvTitle.setText("credit".equals(transactionType) ? "DEPOSIT" : "WITHDRAW");
            btnDone.setText("SAVE TRANSACTION");
        } else {
            // ── Edit mode ──
            transactionType = "credit".equals(editingOriginal.getType()) ? "credit" : "debit";
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

    private void saveTransaction() {
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

        // Get selected payment method
        String paymentMethod = rbCash.isChecked() ? "cash" : "bank";

        boolean ok;
        String successMessage;
        try {
            if (editingId == null) {
                ok = dbHelper.insertTransaction(transactionType, amount, description, paymentMethod);
                successMessage = "✓ Transaction saved";
            } else {
                ok = dbHelper.updateTransaction(editingId, transactionType, amount, description, paymentMethod);
                successMessage = "✓ Transaction updated";
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to save transaction", e);
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
                    boolean deleted;
                    try {
                        deleted = dbHelper.deleteTransaction(editingId);
                    } catch (SQLiteException e) {
                        Log.e(TAG, "Failed to delete transaction", e);
                        deleted = false;
                    }
                    if (deleted) {
                        Toast.makeText(this, "✓ Transaction deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Error deleting. Try again.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
