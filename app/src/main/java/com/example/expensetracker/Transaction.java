package com.example.expensetracker;

/**
 * Model class representing a single financial transaction.
 */
public class Transaction {
    private String id;
    private String type; // "credit" or "debit"
    private double amount;
    private String description;
    private long dateTime;  // Unix timestamp in milliseconds
    private double balanceAfterTransaction;
    private String paymentMethod; // "cash" or "bank"
    private String kind;          // "normal" or "transfer"
    private String transferId;    // non-null only on a transfer

    // Direction, populated only by the grouped history query. A single row read
    // on its own cannot know a direction — only a collapsed pair can.
    private String fromMethod;
    private String toMethod;

    public Transaction(String id, String type, double amount, String description,
                       long dateTime, double balanceAfterTransaction, String paymentMethod,
                       String kind, String transferId, String fromMethod, String toMethod) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.dateTime = dateTime;
        this.balanceAfterTransaction = balanceAfterTransaction;
        this.paymentMethod = paymentMethod;
        this.kind = kind;
        this.transferId = transferId;
        this.fromMethod = fromMethod;
        this.toMethod = toMethod;
    }

    /**
     * True when this row represents a complete transfer.
     * Deliberately stricter than checking kind: an orphaned leg (a transfer row
     * whose partner is missing) has kind 'transfer' but only one direction, and
     * must degrade to rendering as an ordinary row rather than showing "null".
     */
    public boolean isTransfer() {
        return fromMethod != null && toMethod != null;
    }

    public String getId()                       { return id; }
    public String getType()                     { return type; }
    public double getAmount()                   { return amount; }
    public String getDescription()              { return description; }
    public long getDateTime()                   { return dateTime; }
    public double getBalanceAfterTransaction()  { return balanceAfterTransaction; }
    public String getPaymentMethod()            { return paymentMethod; }
    public String getKind()                     { return kind; }
    public String getTransferId()               { return transferId; }
    public String getFromMethod()               { return fromMethod; }
    public String getToMethod()                 { return toMethod; }
}
