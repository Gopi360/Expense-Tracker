package com.example.expensetracker;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays all transactions newest-first with pagination (50 per page).
 */
public class TransactionHistoryActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 50;

    private DatabaseHelper     dbHelper;
    private TransactionAdapter adapter;
    private List<Transaction>  allLoaded;

    private RecyclerView recyclerView;
    private Button       btnLoadMore;
    private TextView     tvEmpty;
    private int          currentOffset = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_history);

        dbHelper   = new DatabaseHelper(this);
        allLoaded  = new ArrayList<>();
        adapter    = new TransactionAdapter(allLoaded);

        recyclerView = findViewById(R.id.recyclerView);
        btnLoadMore  = findViewById(R.id.btnLoadMore);
        tvEmpty      = findViewById(R.id.tvEmpty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Back button
        Button btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Load More button
        btnLoadMore.setOnClickListener(v -> loadNextPage());

        // Initial load
        loadNextPage();
    }

    private void loadNextPage() {
        List<Transaction> page = dbHelper.getTransactions(currentOffset, PAGE_SIZE);

        if (page.isEmpty() && currentOffset == 0) {
            // Nothing at all
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            btnLoadMore.setVisibility(View.GONE);
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);

        adapter.addAll(page);
        currentOffset += page.size();

        int total = dbHelper.getTotalCount();
        if (currentOffset >= total) {
            btnLoadMore.setVisibility(View.GONE);
        } else {
            int remaining = total - currentOffset;
            btnLoadMore.setVisibility(View.VISIBLE);
            btnLoadMore.setText("Load More  (" + remaining + " remaining)");
        }
    }
}
