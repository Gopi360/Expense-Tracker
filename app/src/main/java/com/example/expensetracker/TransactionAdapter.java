package com.example.expensetracker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

    // Dark theme palette
    private static final int COLOR_DEBIT_TEXT  = 0xFFF85149;  // bright red
    private static final int COLOR_CREDIT_TEXT = 0xFF3FB950;  // bright green
    private static final int COLOR_DEBIT_BG    = 0xFF1A0D0D;  // very dark red tint
    private static final int COLOR_CREDIT_BG   = 0xFF0D1A0D;  // very dark green tint
    private static final int COLOR_DIVIDER     = 0xFF30363D;  // dark border
    private static final int COLOR_TRANSFER_TEXT = 0xFF00D4AA;  // teal — neither income nor expense
    private static final int COLOR_TRANSFER_BG   = 0xFF0D1A1A;  // very dark teal tint

    /** Callback for taps on a row. */
    public interface OnTransactionClickListener {
        void onTransactionClick(Transaction transaction);
    }

    private final List<Transaction> transactions;
    private final OnTransactionClickListener clickListener;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("dd MMM\nhh:mm a", Locale.getDefault());

    public TransactionAdapter(List<Transaction> transactions) {
        this(transactions, null);
    }

    public TransactionAdapter(List<Transaction> transactions, OnTransactionClickListener clickListener) {
        this.transactions   = transactions;
        this.clickListener  = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        Transaction t = transactions.get(position);
        boolean isCredit = "credit".equals(t.getType());

        h.tvDateTime.setText(sdf.format(new Date(t.getDateTime())));
        h.tvDescription.setText(t.getDescription());
        h.tvBalance.setText(String.format(Locale.getDefault(), "₹ %.2f", t.getBalanceAfterTransaction()));

        if (t.isTransfer()) {
            // LOAD-BEARING ORDER: this isTransfer() arm must stay ahead of the
            // isCredit check below. getType() on a collapsed transfer group is
            // junk (always "debit" — see Transaction.getType()'s javadoc), so
            // any branch that reads it before checking isTransfer() would
            // render every transfer as a red debit.
            //
            // A transfer moves money without earning or spending it, so it gets
            // neither a sign nor a red/green colour.
            h.tvType.setText("⇄ TRANSFER");
            h.tvAmount.setText(String.format(Locale.getDefault(), "₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_TRANSFER_TEXT);
            h.tvAmount.setTextColor(COLOR_TRANSFER_TEXT);
            h.itemView.setBackgroundColor(COLOR_TRANSFER_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getFromMethod())
                    + " → " + methodIcon(t.getToMethod()));
        } else if (isCredit) {
            h.tvType.setText("▲ CREDIT");
            h.tvAmount.setText(String.format(Locale.getDefault(), "+ ₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_CREDIT_TEXT);
            h.tvAmount.setTextColor(COLOR_CREDIT_TEXT);
            h.itemView.setBackgroundColor(COLOR_CREDIT_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getPaymentMethod()));
        } else {
            h.tvType.setText("▼ DEBIT");
            h.tvAmount.setText(String.format(Locale.getDefault(), "- ₹%.2f", t.getAmount()));
            h.tvType.setTextColor(COLOR_DEBIT_TEXT);
            h.tvAmount.setTextColor(COLOR_DEBIT_TEXT);
            h.itemView.setBackgroundColor(COLOR_DEBIT_BG);
            h.tvPaymentMethod.setText(" • " + methodIcon(t.getPaymentMethod()));
        }

        if (clickListener != null) {
            h.itemView.setOnClickListener(v -> clickListener.onTransactionClick(t));
        }
    }

    @Override
    public int getItemCount() {
        return transactions.size();
    }

    private static String methodIcon(String paymentMethod) {
        return "cash".equals(paymentMethod) ? "💵" : "🏦";
    }

    public void addAll(List<Transaction> more) {
        int start = transactions.size();
        transactions.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    /** Drops every row. Used when the list is reloaded from the first page. */
    public void clear() {
        int removed = transactions.size();
        transactions.clear();
        notifyItemRangeRemoved(0, removed);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDateTime, tvType, tvAmount, tvDescription, tvBalance, tvPaymentMethod;

        ViewHolder(@NonNull View view) {
            super(view);
            tvDateTime    = view.findViewById(R.id.tvDateTime);
            tvType        = view.findViewById(R.id.tvType);
            tvAmount      = view.findViewById(R.id.tvAmount);
            tvDescription = view.findViewById(R.id.tvDescription);
            tvBalance     = view.findViewById(R.id.tvBalance);
            tvPaymentMethod = view.findViewById(R.id.tvPaymentMethod);
        }
    }
}
