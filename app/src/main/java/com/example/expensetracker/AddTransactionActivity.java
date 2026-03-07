package com.example.expensetracker;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class AddTransactionActivity extends AppCompatActivity {

    // Dark theme palette
    private static final int COLOR_GREEN       = 0xFF3FB950;
    private static final int COLOR_RED         = 0xFFF85149;
    private static final int COLOR_GREEN_DIM   = 0xFF0D2B1A;
    private static final int COLOR_RED_DIM     = 0xFF2D0F0F;
    private static final int COLOR_TEAL        = 0xFF00D4AA;

    private EditText       etAmount, etDescription;
    private RadioGroup     rgPaymentMethod;
    private RadioButton    rbCash, rbBank;
    private String         transactionType;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_transaction);

        transactionType = getIntent().getStringExtra("type");
        dbHelper        = new DatabaseHelper(this);

        etAmount      = findViewById(R.id.etAmount);
        etDescription = findViewById(R.id.etDescription);
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod);
        rbCash        = findViewById(R.id.rbCash);
        rbBank        = findViewById(R.id.rbBank);
        TextView tvTypeLabel = findViewById(R.id.tvTypeLabel);
        TextView tvTitle     = findViewById(R.id.tvTitle);
        Button   btnDone     = findViewById(R.id.btnDone);
        Button   btnCancel   = findViewById(R.id.btnCancel);

        if ("credit".equals(transactionType)) {
            tvTitle.setText("DEPOSIT");
            tvTypeLabel.setText("▲  CREDIT — Income");
            tvTypeLabel.setTextColor(COLOR_GREEN);
            tvTypeLabel.setBackgroundColor(COLOR_GREEN_DIM);
            btnDone.setBackgroundTintList(ColorStateList.valueOf(COLOR_GREEN));
            btnDone.setTextColor(0xFF0D1117);
        } else {
            tvTitle.setText("WITHDRAW");
            tvTypeLabel.setText("▼  DEBIT — Expense");
            tvTypeLabel.setTextColor(COLOR_RED);
            tvTypeLabel.setBackgroundColor(COLOR_RED_DIM);
            btnDone.setBackgroundTintList(ColorStateList.valueOf(COLOR_RED));
            btnDone.setTextColor(0xFFFFFFFF);
        }

        btnDone.setOnClickListener(v   -> saveTransaction());
        btnCancel.setOnClickListener(v -> finish());
        etAmount.requestFocus();
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

        boolean ok = dbHelper.insertTransaction(transactionType, amount, description, paymentMethod);
        if (ok) {
            Toast.makeText(this, "✓ Transaction saved", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error saving. Try again.", Toast.LENGTH_SHORT).show();
        }
    }
}
