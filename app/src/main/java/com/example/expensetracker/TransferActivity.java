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
