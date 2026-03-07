package com.example.expensetracker;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView       tvBalance, tvCashBalance, tvBankBalance;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper  = new DatabaseHelper(this);
        tvBalance = findViewById(R.id.tvBalance);
        tvCashBalance = findViewById(R.id.tvCashBalance);
        tvBankBalance = findViewById(R.id.tvBankBalance);

        Button btnCredit  = findViewById(R.id.btnCredit);
        Button btnDebit   = findViewById(R.id.btnDebit);
        Button btnHistory = findViewById(R.id.btnHistory);

        btnCredit.setOnClickListener(v  -> openAddTransaction("credit"));
        btnDebit.setOnClickListener(v   -> openAddTransaction("debit"));
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, TransactionHistoryActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBalance();
    }

    private void openAddTransaction(String type) {
        Intent intent = new Intent(this, AddTransactionActivity.class);
        intent.putExtra("type", type);
        startActivity(intent);
    }

    private void refreshBalance() {
        double balance = dbHelper.getBalance();
        double cashBalance = dbHelper.getCashBalance();
        double bankBalance = dbHelper.getBankBalance();
        
        tvBalance.setText(String.format(Locale.getDefault(), "₹ %.2f", balance));
        tvCashBalance.setText(String.format(Locale.getDefault(), "₹ %.2f", cashBalance));
        tvBankBalance.setText(String.format(Locale.getDefault(), "₹ %.2f", bankBalance));
        
        // Teal = healthy, Red = overdrawn
        tvBalance.setTextColor(balance < 0 ? 0xFFF85149 : 0xFF00D4AA);
        tvCashBalance.setTextColor(cashBalance < 0 ? 0xFFF85149 : 0xFF56D364);
        tvBankBalance.setTextColor(bankBalance < 0 ? 0xFFF85149 : 0xFF6CB6FF);
    }
}
